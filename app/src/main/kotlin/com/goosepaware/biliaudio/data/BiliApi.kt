package com.goosepaware.biliaudio.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * B 站接口：短链解析、视频信息、取流、音轨挑选。
 */
class BiliApi(private val http: BiliHttp) {

  private val bvRe = Regex("(BV[0-9A-Za-z]{10})")
  private val avRe = Regex("av(\\d+)", RegexOption.IGNORE_CASE)
  private val epRe = Regex("(?:ep|ss)(\\d+)", RegexOption.IGNORE_CASE)

  fun resolveUrl(input: String): String {
    // 手机分享文本 = 标题 + 链接，先只取链接部分。
    val url = LinkExtractor.extract(input) ?: input.trim()
    bvRe.matchEntire(url)?.let { return it.groupValues[1] }
    avRe.matchEntire(url)?.let { return "av" + it.groupValues[1] }
    if (url.startsWith("http")) {
      var expanded = url
      if (url.contains("b23.tv") || url.contains("bili2233")) {
        expanded = runCatching {
          http.newRequest(url).get().build().let { req ->
            http.client.newCall(req).execute().use { it.request.url.toString() }
          }
        }.getOrElse { throw BiliException(-1, "短链展开失败: ${it.message}") }
      }
      bvRe.find(expanded)?.let { return it.groupValues[1] }
      avRe.find(expanded)?.let { return "av" + it.groupValues[1] }
      if (epRe.containsMatchIn(expanded)) {
        throw BiliException(-1, "番剧/影视（ep/ss）暂不支持，当前版本只处理普通投稿视频")
      }
    }
    throw BiliException(-1, "无法识别的链接或 ID: $url")
  }

  fun getVideo(vid: String): Video {
    val params = if (vid.startsWith("BV")) mapOf("bvid" to vid) else mapOf("aid" to vid.removePrefix("av"))
    val data = http.apiGet("/x/web-interface/view", params).obj("data")
      ?: throw BiliException(-1, "视频信息为空")

    val pagesJson = data.arr("pages")
    val pages: List<Page> = if (pagesJson == null || pagesJson.isEmpty()) {
      listOf(
        Page(
          index = 1,
          cid = data.long("cid") ?: 0,
          title = data.str("title").orEmpty(),
          duration = data.int("duration") ?: 0,
        )
      )
    } else {
      pagesJson.mapNotNull { el ->
        val p = el.asObj() ?: return@mapNotNull null
        Page(
          index = p.int("page") ?: 1,
          cid = p.long("cid") ?: 0,
          title = p.str("part") ?: "P${p.int("page")}",
          duration = p.int("duration") ?: 0,
        )
      }
    }
    return Video(
      bvid = data.str("bvid").orEmpty(),
      aid = data.long("aid") ?: 0,
      title = data.str("title").orEmpty(),
      owner = data.obj("owner")?.str("name").orEmpty(),
      cover = data.str("pic").orEmpty(),
      pages = pages,
      pubdate = data.long("pubdate") ?: 0,
      desc = data.str("desc").orEmpty(),
    )
  }

  /** 取 DASH 流；fnval=4048 才会带上杜比 / Hi-Res 全部音轨。 */
  fun getPlayurl(bvid: String, cid: Long, qn: Int = 127): JsonObject {
    val params = Wbi.sign(
      http,
      linkedMapOf(
        "bvid" to bvid,
        "cid" to cid,
        "qn" to qn,
        "fnval" to 4048,
        "fnver" to 0,
        "fourk" to 1,
        "otype" to "json",
        "platform" to "pc",
      ),
    )
    return http.apiGet("/x/player/wbi/playurl", params).obj("data")
      ?: throw BiliException(-1, "取流失败")
  }

  fun selectTracks(playurl: JsonObject): List<Track> {
    val dash = playurl.obj("dash") ?: return emptyList()
    val out = mutableListOf<Track>()

    dash.obj("flac")?.obj("audio")?.let { f ->
      out += Track(
        kind = "flac",
        id = f.int("id") ?: 30251,
        codec = f.str("codecs") ?: "fLaC",
        bandwidth = f.int("bandwidth") ?: 0,
        urls = trackUrls(f),
        label = "Hi-Res 无损 FLAC",
        ext = "flac",
      )
    }

    dash.obj("dolby")?.arr("audio")?.forEach { el ->
      val a = el.asObj() ?: return@forEach
      out += Track(
        kind = "dolby",
        id = a.int("id") ?: 30250,
        codec = a.str("codecs") ?: "ec-3",
        bandwidth = a.int("bandwidth") ?: 0,
        urls = trackUrls(a),
        label = "杜比全景声 E-AC3",
        ext = "eac3",
      )
    }

    val aac = dash.arr("audio")?.mapNotNull { it.asObj() }.orEmpty()
    aac.maxByOrNull { it.int("bandwidth") ?: 0 }?.let { best ->
      val kbps = Math.round((best.int("bandwidth") ?: 0) / 1000.0)
      out += Track(
        kind = "aac",
        id = best.int("id") ?: 30280,
        codec = best.str("codecs") ?: "mp4a.40.2",
        bandwidth = best.int("bandwidth") ?: 0,
        urls = trackUrls(best),
        label = "AAC ${kbps}k",
        ext = "m4a",
      )
    }
    return out
  }

  private fun trackUrls(o: JsonObject): List<String> {
    val list = mutableListOf<String>()
    (o.str("baseUrl") ?: o.str("base_url"))?.let { list += it }
    (o.arr("backupUrl") ?: o.arr("backup_url"))?.forEach { el ->
      (el as? JsonPrimitive)?.contentOrNull?.let { list += it }
    }
    return list
  }

  /** 返回 (要下载的轨道, 提示信息)。 */
  fun chooseTracks(tracks: List<Track>, opts: DownloadOptions): Pair<List<Track>, String?> {
    val flac = tracks.firstOrNull { it.kind == "flac" }
    val dolby = tracks.firstOrNull { it.kind == "dolby" }
    val aac = tracks.firstOrNull { it.kind == "aac" }
    val chosen = mutableListOf<Track>()
    var note: String? = null

    if (flac != null) {
      chosen += flac
    } else if (aac != null && opts.aacFallback) {
      chosen += aac
      note = "该视频没有 Hi-Res 无损音轨（UP 主未上传或需要大会员），已回退到最高 AAC"
    } else {
      note = "该视频没有可用的 Hi-Res 无损音轨"
    }
    if (opts.atmos) {
      if (dolby != null) chosen += dolby
      else note = listOfNotNull(note, "该视频没有杜比全景声音轨").joinToString("；")
    }
    return chosen to note
  }

  fun parsePages(spec: String, pages: List<Page>): List<Page> {
    if (spec.isBlank() || spec.equals("all", true)) return pages
    val wanted = mutableSetOf<Int>()
    for (part in spec.split(',')) {
      val s = part.trim()
      if (s.isEmpty()) continue
      if (s.contains('-')) {
        val (a, b) = s.split('-', limit = 2)
        val from = a.toIntOrNull() ?: continue
        val to = b.toIntOrNull() ?: continue
        wanted += from..to
      } else {
        s.toIntOrNull()?.let { wanted += it }
      }
    }
    return pages.filter { it.index in wanted }
  }
}
