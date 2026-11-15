package com.rkteam.cryptolab

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
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
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val uris = WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        web.setBackgroundColor(0xFF050816.toInt())
        setContentView(web)

        // The site is served from the app's assets over a real https origin,
        // so fetch(), storage, audio and speech behave exactly like on a website.
        val loader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
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
                // External links (Telegram, websites...) open outside the app
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                // Make <a download href="blob:..."> save files from inside the app
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

            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny() // no camera / mic needed
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
