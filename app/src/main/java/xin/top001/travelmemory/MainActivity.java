package xin.top001.travelmemory;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * 旅行记忆 的 Android 壳。
 *
 * 与鸿蒙那个壳（travel-memory_harmony）是同一个思路：**不打包网页代码**，运行时
 * 用系统 WebView 加载自托管的 https://top001.xin，把浏览器做不到的原生能力补上：
 *
 *   - 系统返回键：先回退网页历史，退到底才退出 App
 *   - 媒体下载：交给系统 DownloadManager，直接落到公共「下载」目录，
 *     资源管理器 / 相册里能直接看到（比 App 沙箱里翻得出来的体验好）
 *   - 上传：网页 <input type=file> 唤起「相册 / 文件 / 拍照」三选一，支持多选
 *   - 外链：站外 http(s) 与 mailto/tel/sms 交给系统处理
 *   - 定位 / 相机 / 麦克风：网页真正用到时才申请权限
 *   - 加载失败 / 证书错误：可重试的错误页
 *
 * 关于顶部安全区：**Android 15+ 对 targetSdk >= 35 强制 edge-to-edge**，状态栏会直接盖在
 * 内容上；而 Android 的 env(safe-area-inset-top) 只反映屏幕挖孔、不反映状态栏高度，
 * 网页自己那套 env() 在这里靠不住。做法与鸿蒙壳一致：App 全屏铺开（页面背景一直铺到
 * 状态栏底下），然后**实测**状态栏高度，注入网页自己的顶栏（header / .topbar），
 * 并按页面明暗切换状态栏图标颜色。网页若已自己适配（页头带
 * <meta name="tm-safe-area">），就不再补一次，免得双重留白。
 */
public class MainActivity extends Activity {
    private static final String TAG = "TravelMemory";

    /** 网页服务地址。换服务器只改这两行。 */
    private static final String SERVER_URL = "https://top001.xin";
    private static final String SERVER_HOST = "top001.xin";
    private static final String UA_SUFFIX = " TravelMemoryAndroid/1.0";

    private static final int REQ_FILE_CHOOSER = 1001;

    private WebView webView;
    private ProgressBar progressBar;
    private View errorView;
    private TextView errorText;
    private FrameLayout rootView;

    /** 状态栏高度（px），由 WindowInsets 实测得到，不用写死。 */
    private int topInsetPx = 0;

    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraOutputUri;

    /** 运行时权限：requestCode -> 回调，granted / denied 各一个。 */
    private int permissionSeq = 2000;
    private final HashMap<Integer, Runnable> permissionGranted = new HashMap<>();
    private final HashMap<Integer, Runnable> permissionDenied = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Android 15+ 对 targetSdk>=35 本来就是边到边；这里在更老的版本上显式打开，
        // 让各版本行为一致（不打开的话老版本会被系统留出一条固定状态栏，页面就
        // 不可能「背景铺到状态栏底下」）。
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        buildUi();
        configureWebView();
    }

    // ------------------------------------------------------------------
    // 界面（用代码搭，省掉一份 layout XML —— 这里只有三个控件）
    // ------------------------------------------------------------------

    private void buildUi() {
        rootView = new FrameLayout(this);
        rootView.setBackgroundColor(Color.WHITE);

        webView = new WebView(this);
        rootView.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        barParams.gravity = Gravity.TOP;
        rootView.addView(progressBar, barParams);

        errorView = buildErrorView();
        errorView.setVisibility(View.GONE);
        rootView.addView(errorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(rootView);

        // 状态栏高度只有挂上监听才知道。拿到就顺手把进度条也挪下来，
        // 并重新给网页做一次补偿（首次 onPageFinished 时可能还没测出来）。
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            if (bars.top != topInsetPx) {
                topInsetPx = bars.top;
                FrameLayout.LayoutParams lp =
                        (FrameLayout.LayoutParams) progressBar.getLayoutParams();
                lp.topMargin = topInsetPx;
                progressBar.setLayoutParams(lp);
                applyImmersiveChrome();
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(rootView);
    }

    private View buildErrorView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.parseColor("#FFF6EE"));
        box.setPadding(dp(32), dp(32), dp(32), dp(32));

        TextView icon = new TextView(this);
        icon.setText("🧭");
        icon.setTextSize(48);
        box.addView(icon);

        TextView title = new TextView(this);
        title.setText("暂时无法连接到旅行记忆");
        title.setTextSize(18);
        title.setTextColor(Color.parseColor("#3B2A16"));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(12);
        box.addView(title, titleParams);

        errorText = new TextView(this);
        errorText.setText("请检查网络连接后重试");
        errorText.setTextSize(13);
        errorText.setTextColor(Color.parseColor("#8A5A2B"));
        errorText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textParams.topMargin = dp(8);
        box.addView(errorText, textParams);

        Button retry = new Button(this);
        retry.setText("重新加载");
        retry.setOnClickListener(v -> {
            hideError();
            webView.loadUrl(SERVER_URL);
        });
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnParams.topMargin = dp(16);
        box.addView(retry, btnParams);

        return box;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    // ------------------------------------------------------------------
    // WebView
    // ------------------------------------------------------------------

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMediaPlaybackRequiresUserGesture(false);   // 路线回放的自动播放
        s.setGeolocationEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // 保留原生 UA 再追加标记，服务端可以据此区分「App」和「手机浏览器」
        String ua = s.getUserAgentString();
        if (ua != null && !ua.contains("TravelMemoryAndroid")) {
            s.setUserAgentString(ua + UA_SUFFIX);
        }

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new ShellWebViewClient());
        webView.setWebChromeClient(new ShellChromeClient());
        webView.setDownloadListener(this::enqueueDownload);

        // 网页下载桥：window.Android.saveMedia(url, name) 直接存到相册
        webView.addJavascriptInterface(new GalleryBridge(this), "Android");

        webView.loadUrl(SERVER_URL);
    }

    private class ShellWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (isInternal(url)) {
                return false;                     // 站内导航留在 App 里
            }
            openExternally(url);
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            hideError();
            progressBar.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            progressBar.setVisibility(View.GONE);
            applyImmersiveChrome();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                showError(String.valueOf(error.getDescription()));
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
            Log.e(TAG, "SSL error: " + error.getUrl());
            handler.cancel();                     // 证书有问题一律不放过
            showError("证书校验失败，无法建立安全连接");
        }
    }

    private class ShellChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progressBar.setProgress(newProgress);
            progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            // 网页要定位 -> 先要系统权限，拿到才允许
            ask(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    () -> callback.invoke(origin, true, false),
                    () -> callback.invoke(origin, false, false));
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            List<String> need = new ArrayList<>();
            for (String resource : request.getResources()) {
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource) && !need.contains(Manifest.permission.CAMERA)) {
                    need.add(Manifest.permission.CAMERA);
                } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource) && !need.contains(Manifest.permission.RECORD_AUDIO)) {
                    need.add(Manifest.permission.RECORD_AUDIO);
                }
            }
            if (need.isEmpty()) {
                request.deny();
                return;
            }
            ask(need.toArray(new String[0]),
                    () -> request.grant(request.getResources()),
                    request::deny);
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);       // 上一次没走完，先清掉
            }
            filePathCallback = callback;
            cameraOutputUri = null;

            Intent content = new Intent(Intent.ACTION_GET_CONTENT);
            content.addCategory(Intent.CATEGORY_OPENABLE);
            applyAcceptTypes(content, params.getAcceptTypes());
            content.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

            Intent chooser = Intent.createChooser(content, "选择照片、视频或文件");
            Intent camera = buildCameraIntent();
            if (camera != null) {
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
            }
            try {
                startActivityForResult(chooser, REQ_FILE_CHOOSER);
            } catch (Exception e) {
                Log.e(TAG, "no activity for file chooser: " + e);
                filePathCallback = null;
                return false;
            }
            return true;
        }
    }

    /** 网页 accept 是 image/*,video/* 时，让系统选择器同时认这两类。 */
    private void applyAcceptTypes(Intent intent, String[] acceptTypes) {
        boolean image = false;
        boolean video = false;
        if (acceptTypes != null) {
            for (String type : acceptTypes) {
                if (type == null) continue;
                String t = type.toLowerCase(Locale.ROOT);
                if (t.startsWith("image")) image = true;
                else if (t.startsWith("video")) video = true;
            }
        }
        if (image || video) {
            List<String> mimes = new ArrayList<>();
            if (image) mimes.add("image/*");
            if (video) mimes.add("video/*");
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
        } else {
            intent.setType("*/*");
        }
    }

    /** 「拍照」这一项：照片先写进自己的缓存目录，回来再当作选择结果交给网页。 */
    private Intent buildCameraIntent() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            return null;
        }
        try {
            File dir = new File(getCacheDir(), "capture");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            File out = new File(dir, "cap_" + System.currentTimeMillis() + ".jpg");
            cameraOutputUri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", out);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraOutputUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return intent;
        } catch (Exception e) {
            Log.e(TAG, "cannot prepare camera output: " + e);
            cameraOutputUri = null;
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_FILE_CHOOSER) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            if (data == null || (data.getData() == null && data.getClipData() == null)) {
                // 拍照那条路：相机把图写进了我们给的 URI，data 是空的
                if (cameraOutputUri != null) {
                    result = new Uri[]{cameraOutputUri };
                }
            } else if (data.getClipData() != null) {
                ClipData clip = data.getClipData();
                result = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) {
                    result[i] = clip.getItemAt(i).getUri();
                }
            } else {
                result = new Uri[]{ data.getData() };
            }
        }
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
        }
        cameraOutputUri = null;
    }

    /** 网页下载桥：window.Android.saveMedia(url, name) -> 存到公共 Pictures/TravelMemory（相册可见）。 */
    private class GalleryBridge {
        private final MainActivity owner;
        GalleryBridge(MainActivity o) { this.owner = o; }
        @JavascriptInterface
        public void saveMedia(String url, String name) {
            // DownloadManager 需要在 UI 线程发起
            owner.runOnUiThread(() -> owner.enqueueToGallery(url, name));
        }
    }

    // ------------------------------------------------------------------
    // 下载：交给系统 DownloadManager，直接落到公共「下载」目录
    // ------------------------------------------------------------------

    private void enqueueDownload(String url, String userAgent, String contentDisposition,
                                 String mimeType, long contentLength) {
        // Android 9 及以下写到公共「下载」目录需要写权限；10 起 DownloadManager 自己处理。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
            ask(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    () -> doEnqueueDownload(url, userAgent, contentDisposition, mimeType),
                    () -> toast("没有存储权限，无法保存到「下载」"));
            return;
        }
        doEnqueueDownload(url, userAgent, contentDisposition, mimeType);
    }

    private void doEnqueueDownload(String url, String userAgent, String contentDisposition,
                                   String mimeType) {
        try {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setMimeType(mimeType);
            request.setTitle(fileName);
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            if (userAgent != null) {
                request.addRequestHeader("User-Agent", userAgent);
            }
            // DownloadManager 不共享 WebView 的会话，签名 URL 之外还可能带 cookie，
            // 这里手动带上，免得「浏览器里能下、App 里 401」。
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) {
                request.addRequestHeader("Cookie", cookie);
            }
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(request);
                toast("已开始下载：" + fileName);
            }
        } catch (Exception e) {
            Log.e(TAG, "download failed: " + e);
            toast("下载失败，请重试");
        }
    }

    // 网页下载桥：把媒体存到公共 Pictures/TravelMemory，相册里直接可见。
    private void enqueueToGallery(String url, String name) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
            ask(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    () -> doEnqueueToGallery(url, name),
                    () -> toast("没有存储权限，无法保存到相册"));
            return;
        }
        doEnqueueToGallery(url, name);
    }

    private void doEnqueueToGallery(String url, String name) {
        try {
            String fileName = (name != null && !name.isEmpty())
                    ? name : URLUtil.guessFileName(url, null, null);
            String lower = fileName.toLowerCase(Locale.ROOT);
            boolean isVideo = lower.endsWith(".mp4") || lower.endsWith(".mov")
                    || lower.endsWith(".webm") || lower.endsWith(".m4v") || lower.endsWith(".avi");
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setMimeType(isVideo ? "video/mp4" : "image/jpeg");
            request.setTitle(fileName);
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_PICTURES, "TravelMemory/" + fileName);
            // DownloadManager 不共享 WebView 的会话，手动带上 tm_token cookie，
            // 免得「浏览器里能下、App 里 401」。
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) {
                request.addRequestHeader("Cookie", cookie);
            }
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(request);
                toast("已开始保存到相册：" + fileName);
            }
        } catch (Exception e) {
            Log.e(TAG, "gallery download failed: " + e);
            toast("保存失败，请重试");
        }
    }

    // ------------------------------------------------------------------
    // 外链
    // ------------------------------------------------------------------

    private boolean isInternal(String url) {
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals(SERVER_HOST) || host.endsWith("." + SERVER_HOST);
    }

    private void openExternally(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "no app for " + url + ": " + e);
            toast("无法打开链接");
        }
    }

    // ------------------------------------------------------------------
    // 返回键：先走网页历史
    // ------------------------------------------------------------------

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------
    // 运行时权限
    // ------------------------------------------------------------------

    private void ask(String[] permissions, Runnable onGranted, Runnable onDenied) {
        boolean missing = false;
        for (String p : permissions) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                missing = true;
                break;
            }
        }
        if (!missing) {
            onGranted.run();
            return;
        }
        int code = ++permissionSeq;
        permissionGranted.put(code, onGranted);
        permissionDenied.put(code, onDenied);
        requestPermissions(permissions, code);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Runnable granted = permissionGranted.remove(requestCode);
        Runnable denied = permissionDenied.remove(requestCode);
        boolean all = grantResults.length > 0;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                all = false;
                break;
            }
        }
        if (all) {
            if (granted != null) granted.run();
        } else if (denied != null) {
            denied.run();
        }
    }

    // ------------------------------------------------------------------
    // 顶部安全区（Android 15+ 强制边到边，得自己把网页顶栏顶开）
    // ------------------------------------------------------------------

    /**
     * 实测状态栏高度 -> 注入网页顶栏的内边距 -> 按页面明暗切图标颜色。
     * 每次都跑（幂等）：页面切浅色/深色、系统栏高度变化（转屏、折叠）都能跟上。
     */
    private void applyImmersiveChrome() {
        if (webView == null || topInsetPx <= 0) {
            return;
        }
        final int topVp = Math.round(topInsetPx / getResources().getDisplayMetrics().density);
        webView.evaluateJavascript(buildInsetScript(topVp), tone -> {
            boolean dark = tone != null && tone.contains("dark");
            WindowInsetsControllerCompat controller =
                    WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            controller.setAppearanceLightStatusBars(!dark);
            controller.setAppearanceLightNavigationBars(!dark);
        });
    }

    /**
     * 与鸿蒙壳注入的是同一段逻辑：
     *   1. 读页面背景亮度，决定状态栏图标用深色还是浅色；
     *   2. 页面**没有**声明 <meta name="tm-safe-area"> 时，才把实测的
     *      状态栏高度补到 header / .topbar 上，并顺手补 viewport-fit=cover，
     *      让网页自己的 env(safe-area-inset-bottom) 也能生效；
     *   3. 幂等：同一个文档只注入一次，之后只回读明暗。
     */
    private String buildInsetScript(int topVp) {
        return "(function(){try{"
                + "if(!document.body)return 'nobody';"
                + "if(window.__tmSafe){return window.__tmTone||'light';}"
                + "var bg=getComputedStyle(document.body).backgroundColor||'';"
                + "var n=bg.match(/[0-9]+/g)||[];"
                + "var r=parseInt(n[0]||'255',10),g=parseInt(n[1]||'255',10),b=parseInt(n[2]||'255',10);"
                + "var dark=(0.299*r+0.587*g+0.114*b)<140;"
                + "window.__tmTone=dark?'dark':'light';"
                + "window.__tmSafe=1;"
                + "if(!document.querySelector('meta[name=tm-safe-area]')){"
                + "  var px=" + topVp + ";"
                + "  var mv=document.querySelector('meta[name=viewport]');"
                + "  if(mv){var c=mv.getAttribute('content')||'';"
                + "    if(c.indexOf('viewport-fit')<0)mv.setAttribute('content',c+', viewport-fit=cover');}"
                + "  var st=document.createElement('style');"
                + "  st.id='tm-safe-area';"
                + "  st.textContent='header{padding-top:'+px+'px !important}"
                + ".topbar{padding-top:'+px+'px !important}';"
                + "  document.head.appendChild(st);"
                + "}"
                + "return window.__tmTone;"
                + "}catch(e){return 'err:'+e}})()";
    }

    // ------------------------------------------------------------------
    // 错误页 / 提示
    // ------------------------------------------------------------------

    private void showError(String message) {
        Log.e(TAG, "load error: " + message);
        errorText.setText(message == null || message.isEmpty() ? "请检查网络连接后重试" : message);
        errorView.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
    }

    private void hideError() {
        errorView.setVisibility(View.GONE);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
