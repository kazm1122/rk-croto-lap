package com.rkteam.cryptolab

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.util.UUID
import org.json.JSONObject
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    // ====== رابط التحديث عن بعد: الملف الذي تعدّله على GitHub ======
    private val updateUrl =
        "https://raw.githubusercontent.com/kazm1122/rk-croto-lap/main/RK-Crypto-Lab-Android/app/src/main/assets/index.html"

    // ====== إحصائيات الاستخدام (رابط Google Apps Script) — اتركه فارغاً لتعطيلها ======
    private val statsUrl = ""

    // ====== ملف التحكم عن بعد (رسالة للمستخدمين / إيقاف التطبيق) ======
    private val configUrl =
        "https://raw.githubusercontent.com/kazm1122/rk-croto-lap/main/RK-Crypto-Lab-Android/remote-config.json"

    private lateinit var web: WebView
    private lateinit var localPage: File
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermRequest: PermissionRequest? = null

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val uris = WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
        }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val req = pendingPermRequest
            pendingPermRequest = null
            if (req != null) grantWebPermission(req)
        }

    private val firstRunLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        localPage = File(filesDir, "index.html")
        dropStaleCache()

        web = WebView(this)
        web.setBackgroundColor(0xFF050816.toInt())
        setContentView(web)

        // Serve the site from the app over a real https origin.
        // If a newer downloaded copy exists it is used, otherwise the bundled one.
        val assetsHandler = WebViewAssetLoader.AssetsPathHandler(this)
        val loader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/") { path ->
                if (path == "index.html" && localPage.exists())
                    WebResourceResponse("text/html", "utf-8", localPage.inputStream())
                else assetsHandler.handle(path)
            }
            .build()

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(false)
            textZoom = 100
        }

        web.addJavascriptInterface(Bridge(), "AndroidBridge")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "appassets.androidplatform.net") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(BLOB_HOOK, null)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    pickFile.launch(params.createIntent()); true
                } catch (e: Exception) {
                    filePathCallback = null; false
                }
            }

            // Camera / microphone requested by the page
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (request.origin.host != "appassets.androidplatform.net") {
                        request.deny(); return@runOnUiThread
                    }
                    val missing = neededAndroidPerms(request).filter {
                        ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isEmpty()) grantWebPermission(request)
                    else {
                        pendingPermRequest?.deny()
                        pendingPermRequest = request
                        permLauncher.launch(missing.toTypedArray())
                    }
                }
            }
        }

        web.setDownloadListener { url, _, contentDisposition, mime, _ ->
            if (url.startsWith("blob:")) {
                val name = URLUtil.guessFileName(url, contentDisposition, mime)
                web.evaluateJavascript(blobScript(url, name), null)
            } else {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        showPrivacyNoticeOnce()
        checkForUpdate()
        sendStat("open")
        checkRemoteConfig()
    }

    // ---------- Permissions ----------
    private fun neededAndroidPerms(r: PermissionRequest): List<String> {
        val l = mutableListOf<String>()
        for (res in r.resources) {
            if (res == PermissionRequest.RESOURCE_VIDEO_CAPTURE) l.add(Manifest.permission.CAMERA)
            if (res == PermissionRequest.RESOURCE_AUDIO_CAPTURE) l.add(Manifest.permission.RECORD_AUDIO)
        }
        return l
    }

    private fun grantWebPermission(r: PermissionRequest) {
        val ok = r.resources.filter {
            (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE &&
                has(Manifest.permission.CAMERA)) ||
            (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE &&
                has(Manifest.permission.RECORD_AUDIO))
        }
        if (ok.isEmpty()) r.deny() else r.grant(ok.toTypedArray())
    }

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // Ask once at first launch so the user doesn't get surprised later
    private fun askPermissionsFirstRun() {
        val prefs = getSharedPreferences("rk", MODE_PRIVATE)
        if (prefs.getBoolean("asked", false)) return
        prefs.edit().putBoolean("asked", true).apply()
        val list = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        firstRunLauncher.launch(list.toTypedArray())
    }

    // ---------- Remote update ----------
    // A downloaded copy older than the installed APK is discarded (APK update wins).
    private fun dropStaleCache() {
        try {
            val installed = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
            if (localPage.exists() && localPage.lastModified() < installed) localPage.delete()
        } catch (_: Exception) {}
    }

    private fun checkForUpdate() {
        Thread {
            try {
                val c = URL(updateUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 20000
                c.setRequestProperty("Cache-Control", "no-cache")
                if (c.responseCode != 200) return@Thread
                val bytes = c.inputStream.use { it.readBytes() }
                val text = String(bytes, Charsets.UTF_8)
                if (bytes.size < 50_000 || !text.contains("</html>")) return@Thread // sanity check

                val current = if (localPage.exists()) localPage.readBytes()
                              else assets.open("index.html").use { it.readBytes() }
                if (current.contentEquals(bytes)) return@Thread

                val tmp = File(filesDir, "index.html.tmp")
                FileOutputStream(tmp).use { it.write(bytes) }
                if (!tmp.renameTo(localPage)) { tmp.copyTo(localPage, true); tmp.delete() }

                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    AlertDialog.Builder(this)
                        .setTitle("تحديث جديد")
                        .setMessage("تم تنزيل نسخة أحدث من التطبيق. هل تريد التحديث الآن؟")
                        .setPositiveButton("تحديث الآن") { _, _ -> web.reload() }
                        .setNegativeButton("لاحقاً", null)
                        .show()
                }
            } catch (_: Exception) {
                // offline or GitHub unreachable: keep working with current copy
            }
        }.start()
    }

    // ---------- Anonymous usage stats (with user notice + opt-out) ----------
    private fun showPrivacyNoticeOnce() {
        val prefs = getSharedPreferences("rk", MODE_PRIVATE)
        if (prefs.contains("stats")) { askPermissionsFirstRun(); return }
        AlertDialog.Builder(this)
            .setTitle("إحصائيات الاستخدام")
            .setMessage("لتحسين التطبيق نسجّل بيانات مجهولة فقط: عدد مرات الفتح، إصدار التطبيق، نوع الجهاز وإصدار أندرويد. لا نسجّل اسمك أو رقمك أو صورك أو أي محتوى تُدخله.")
            .setCancelable(false)
            .setPositiveButton("موافق") { _, _ ->
                prefs.edit().putBoolean("stats", true).apply()
                sendStat("open"); askPermissionsFirstRun()
            }
            .setNegativeButton("لا أوافق") { _, _ ->
                prefs.edit().putBoolean("stats", false).apply(); askPermissionsFirstRun()
            }
            .show()
    }

    private fun sendStat(event: String) {
        val prefs = getSharedPreferences("rk", MODE_PRIVATE)
        if (statsUrl.isBlank() || !prefs.getBoolean("stats", false)) return
        var id = prefs.getString("iid", null)
        if (id == null) { id = UUID.randomUUID().toString().take(8); prefs.edit().putString("iid", id).apply() }
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        val body = JSONObject()
            .put("id", id).put("event", event).put("appVersion", ver)
            .put("android", Build.VERSION.RELEASE).put("model", Build.MODEL)
            .put("lang", java.util.Locale.getDefault().language).toString()
        Thread {
            try {
                val c = URL(statsUrl).openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.instanceFollowRedirects = false
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
                c.outputStream.use { it.write(body.toByteArray()) }
                c.responseCode
            } catch (_: Exception) {}
        }.start()
    }

    // ---------- Remote control: announcement / kill switch ----------
    private fun checkRemoteConfig() {
        Thread {
            try {
                val c = URL(configUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.setRequestProperty("Cache-Control", "no-cache")
                if (c.responseCode != 200) return@Thread
                val j = JSONObject(c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
                val enabled = j.optBoolean("enabled", true)
                val msg = j.optString("message", "")
                val offMsg = j.optString("disabledMessage", "التطبيق متوقف مؤقتاً.")
                val prefs = getSharedPreferences("rk", MODE_PRIVATE)
                val reqArr = j.optJSONArray("requestPermissions")
                val names = (0 until (reqArr?.length() ?: 0)).map { reqArr!!.getString(it) }
                val permMsg = j.optString("permissionMessage", "")
                val permId = j.optString("permissionRequestId", "")
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (enabled && names.isNotEmpty() && permId.isNotBlank() &&
                        prefs.getString("lastPermId", "") != permId) {
                        prefs.edit().putString("lastPermId", permId).apply()
                        askRemotePermissions(names, permMsg)
                    }
                    if (!enabled) {
                        AlertDialog.Builder(this).setTitle("إعلان").setMessage(offMsg)
                            .setCancelable(false)
                            .setPositiveButton("إغلاق") { _, _ -> finishAffinity() }.show()
                    } else if (msg.isNotBlank() && prefs.getString("lastMsg", "") != msg) {
                        prefs.edit().putString("lastMsg", msg).apply()
                        AlertDialog.Builder(this).setTitle("إعلان").setMessage(msg)
                            .setPositiveButton("حسناً", null).show()
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    // The owner can ask for permissions remotely; the user always sees a message and can decline.
    private fun askRemotePermissions(names: List<String>, why: String) {
        val list = names.mapNotNull {
            when (it) {
                "camera" -> Manifest.permission.CAMERA
                "microphone" -> Manifest.permission.RECORD_AUDIO
                "notifications" -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
                else -> null
            }
        }.filter { !has(it) }
        if (list.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("طلب إذن")
            .setMessage(if (why.isBlank()) "يحتاج التطبيق إذناً لتشغيل ميزة جديدة." else why)
            .setPositiveButton("متابعة") { _, _ -> firstRunLauncher.launch(list.toTypedArray()) }
            .setNegativeButton("ليس الآن", null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() { super.onPause(); web.onPause() }
    override fun onResume() { super.onResume(); web.onResume() }
    override fun onDestroy() { web.destroy(); super.onDestroy() }

    inner class Bridge {
        @JavascriptInterface
        fun saveFile(name: String, mime: String, base64: String) {
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                if (Build.VERSION.SDK_INT >= 29) {
                    val cv = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safe)
                        put(MediaStore.Downloads.MIME_TYPE, mime.ifEmpty { "application/octet-stream" })
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
                    contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    FileOutputStream(File(dir, safe)).use { it.write(bytes) }
                }
                toast("تم الحفظ في مجلد التنزيلات: $safe")
            } catch (e: Exception) {
                toast("تعذّر حفظ الملف")
            }
        }
    }

    private fun toast(msg: String) =
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    private fun jsStr(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    private fun blobScript(url: String, name: String) = """
        (function(){
          var x=new XMLHttpRequest();x.open('GET',${jsStr(url)},true);x.responseType='blob';
          x.onload=function(){var r=new FileReader();r.onloadend=function(){
            AndroidBridge.saveFile(${jsStr(name)},x.response.type||'',(r.result.split(',')[1]||''));
          };r.readAsDataURL(x.response);};x.send();
        })();
    """.trimIndent()

    companion object {
        private const val BLOB_HOOK = """
            (function(){
              if(window.__rkHook)return;window.__rkHook=1;
              document.addEventListener('click',function(e){
                var a=e.target.closest&&e.target.closest('a[download]');
                if(!a||!/^blob:/.test(a.href))return;
                e.preventDefault();
                var name=a.getAttribute('download')||'file';
                var x=new XMLHttpRequest();x.open('GET',a.href,true);x.responseType='blob';
                x.onload=function(){var r=new FileReader();r.onloadend=function(){
                  AndroidBridge.saveFile(name,x.response.type||'',(r.result.split(',')[1]||''));
                };r.readAsDataURL(x.response);};x.send();
              },true);
            })();
        """
    }
}
