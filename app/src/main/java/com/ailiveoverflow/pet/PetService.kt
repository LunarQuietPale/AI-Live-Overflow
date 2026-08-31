package com.ailiveoverflow.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationManagerCompat
import kotlin.random.Random
import java.net.HttpURLConnection
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader
import org.json.JSONObject
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.FileObserver
import android.os.Environment
import java.io.File
import android.os.Looper
import android.provider.Settings
import android.database.ContentObserver
import android.net.Uri
import android.provider.MediaStore
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebSettings
import android.app.usage.UsageStatsManager
import android.app.usage.UsageEvents
import android.app.AppOpsManager

class PetService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var petView: View
    private lateinit var webView: WebView

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    // 双击检测
    private var lastTapTime = 0L
    private var downTime = 0L
    private var pendingSingleTap = false
    // 长按检测
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressTriggered = false
    private val longPressRunnable = Runnable {
        longPressTriggered = true
        webView.evaluateJavascript("window.petHide();", null)
    }

    // ---- 感知系统 ----
    private var batteryReceiver: BroadcastReceiver? = null
    private var screenshotObserver: FileObserver? = null
    private var lastScreenshotTime = 0L
    private var lastBatteryPct = -1
    private var lastChargeState = -1  // -1未知 0未充电 1充电
    private var lastTimeGreeting = -1  // 记录上次打招呼的时段，避免重复
    private var screenshotObserver2: ContentObserver? = null
    private val senseHandler = Handler(Looper.getMainLooper())
    // ---- 独立气泡悬浮窗 ----
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private val bubbleHandler = Handler(Looper.getMainLooper())
    private val bubbleHideRunnable = Runnable { hideBubbleWindow() }
    // ---- 表达系统：通知碎碎念 ----
    private val notifyHandler = Handler(Looper.getMainLooper())
    private val notifyRunnable = object : Runnable {
        override fun run() {
            sendChatterNotification()
            notifyHandler.postDelayed(this, 5 * 60 * 1000 + (Math.random() * 5 * 60 * 1000).toLong())
        }
    }

    // ---- 喝水提醒（每2小时） ----
    private val drinkHandler = Handler(Looper.getMainLooper())
    private val drinkRunnable = object : Runnable {
        override fun run() {
            sendDrinkReminder()
            drinkHandler.postDelayed(this, 2 * 60 * 60 * 1000)
        }
    }
    // ---- 情绪引擎：Supabase轮询 ----
    private val supabaseUrl = "https://gljhdigxpvldpnmqougr.supabase.co"
    private val supabaseKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImdsamhkaWd4cHZsZHBubXFvdWdyIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODY5OTAyMjQsImV4cCI6MjEwMjU2NjIyNH0.6k6g9N-TOh0p6dhA9Bw1wElys5HbEQ5dLsOdUehLtm8"
    private val emotionHandler = Handler(Looper.getMainLooper())
    private var lastEmotion = ""
    private val emotionRunnable = object : Runnable {
        override fun run() {
            pollEmotion()
            emotionHandler.postDelayed(this, 15000 + (Math.random() * 15000).toLong())
        }
    }
    // ---- 前台App检测（UsageStatsManager，每3秒轮询） ----
    private val appHandler = Handler(Looper.getMainLooper())
    private var lastForegroundApp = ""
    private val appRunnable = object : Runnable {
        override fun run() {
            checkForegroundApp()
            appHandler.postDelayed(this, 3000)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, buildNotification())
        }
        showPetWindow()
        setupSensing()
        // 启动表达系统：通知碎碎念（5-10分钟一条）
        notifyHandler.postDelayed(notifyRunnable, 5 * 60 * 1000)
        // 启动情绪引擎：轮询Supabase（15-30秒一次）
        emotionHandler.postDelayed(emotionRunnable, 15000)
        // 启动喝水提醒（每2小时）
        drinkHandler.postDelayed(drinkRunnable, 2 * 60 * 60 * 1000)
        // 启动前台App检测（每3秒）
        appHandler.postDelayed(appRunnable, 3000)
    }

    // ---- 感知系统初始化 ----
    private fun setupSensing() {
        // 1. 充电/低电量检测
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                handleBattery(intent)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(batteryReceiver, filter)
        // 2. 时段感知（启动时打招呼一次，等页面加载完再调，见 onPageFinished）
        // 3. 截图检测
        setupScreenshotObserver()
    }

    // 充电/低电量处理
    private fun handleBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        // 充电状态变化
        if (charging && lastChargeState != 1) {
            lastChargeState = 1
            webView.evaluateJavascript("window.petSense('charge');", null)
        } else if (!charging && lastChargeState == 1) {
            lastChargeState = 0
        }
        // 低电量提醒（低于20%且未充电，只提醒一次）
        if (pct > 0 && pct <= 20 && !charging && lastBatteryPct != pct) {
            webView.evaluateJavascript("window.petSense('lowbattery');", null)
        }
        lastBatteryPct = pct
    }

    // 时段感知：启动时打招呼
    private fun checkTimeGreeting() {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (hour != lastTimeGreeting) {
            lastTimeGreeting = hour
            webView.evaluateJavascript("window.petSense('time', $hour);", null)
        }
    }
    // 前台App检测：每3秒轮询，切换时触发petSense('app', pkg)
    private fun checkForegroundApp() {
        try {
            // 检查是否有"使用情况访问"权限
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
            else
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
            if (mode != AppOpsManager.MODE_ALLOWED) return  // 没授权就跳过，避免刷屏

            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val begin = end - 10 * 1000  // 只看最近10秒
            val events = usm.queryEvents(begin, end)
            var current: String? = null
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    current = event.packageName
                }
            }
            if (current != null && current != lastForegroundApp) {
                lastForegroundApp = current
                webView.evaluateJavascript("window.petSense('app', '$current');", null)
            }
        } catch (e: Exception) {
            // 权限未授予或异常时静默跳过
        }
    }
    // 截图检测：ContentObserver监听MediaStore（更可靠，覆盖所有截图路径）
    private fun setupScreenshotObserver() {
        try {
            // 方式1：FileObserver监听Pictures/Screenshots目录
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val screenshots = File(dir, "Screenshots")
            if (!screenshots.exists()) screenshots.mkdirs()
            screenshotObserver = object : FileObserver(screenshots.absolutePath, FileObserver.CLOSE_WRITE) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && (path.endsWith(".png") || path.endsWith(".jpg"))) {
                        triggerScreenshot()
                    }
                }
            }
            screenshotObserver?.startWatching()
        } catch (e: Exception) {
            // 目录不可用就跳过
        }
        // 方式2：ContentObserver监听MediaStore图片变化（双保险，覆盖DCIM等路径）
        try {
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            screenshotObserver2 = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    triggerScreenshot()
                }
            }
            contentResolver.registerContentObserver(uri, true, screenshotObserver2!!)
        } catch (e: Exception) {
            // 忽略
        }
    }
    // 截图触发（带防抖）
    private fun triggerScreenshot() {
        val now = System.currentTimeMillis()
        if (now - lastScreenshotTime > 2000) {
            lastScreenshotTime = now
            webView.evaluateJavascript("window.petSense('screenshot');", null)
        }
    }

    private fun showPetWindow() {
        if (!Settings.canDrawOverlays(this)) return

        val layoutParams = WindowManager.LayoutParams(
            280,
            280,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        petView = LayoutInflater.from(this).inflate(R.layout.pet_window, null)
        webView = petView.findViewById(R.id.petWebView)
        setupWebView()

        // WebView会默认消费触摸事件导致外层拖拽失效，这里统一拦截并转发给handleTouch
        val touchListener = View.OnTouchListener { _, event ->
            handleTouch(event, layoutParams)
            true
        }
        petView.setOnTouchListener(touchListener)
        webView.setOnTouchListener(touchListener)

        windowManager.addView(petView, layoutParams)
    }

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.allowFileAccess = true
        settings.setSupportZoom(false)
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        webView.setBackgroundColor(0x00000000)
        webView.addJavascriptInterface(BubbleBridge(), "Android")
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 页面加载完再打招呼，避免 petSense 未定义报错
                checkTimeGreeting()
            }
        }
        webView.loadUrl("file:///android_asset/pet.html")
    }

    // ---- 独立气泡悬浮窗 ----
    private inner class BubbleBridge {
        @android.webkit.JavascriptInterface
        fun showBubble(text: String) {
            bubbleHandler.post {
                showBubbleWindow(text)
            }
        }
    }
    private fun showBubbleWindow(text: String) {
        if (!Settings.canDrawOverlays(this)) return
        bubbleHandler.removeCallbacks(bubbleHideRunnable)
        notifyHandler.removeCallbacks(notifyRunnable)
        if (bubbleView == null) {
            val tv = android.widget.TextView(this)
            tv.setTextColor(0xFF7EC8E3.toInt())
            tv.textSize = 13f
            tv.setTypeface(null, android.graphics.Typeface.BOLD)
            tv.setPadding(28, 14, 28, 14)
            tv.setSingleLine(true)
            val radius = 40f
            val bg = android.graphics.drawable.GradientDrawable()
            bg.cornerRadius = radius
            bg.setColor(0xD9FFFFFF.toInt())
            tv.background = bg
            tv.text = text
            bubbleView = tv
            bubbleParams = WindowManager.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 300
            }
            windowManager.addView(tv, bubbleParams)
        } else {
            (bubbleView as android.widget.TextView).text = text
        }
        positionBubbleBelowPet()
        bubbleHandler.postDelayed(bubbleHideRunnable, 2500)
    }
    private fun positionBubbleBelowPet() {
        val bp = bubbleParams ?: return
        if (!::petView.isInitialized) return
        val lp = petView.layoutParams as? WindowManager.LayoutParams ?: return
        bp.x = lp.x
        bp.y = lp.y - 70
        bubbleView?.let { windowManager.updateViewLayout(it, bp) }
    }
    private fun hideBubbleWindow() {
        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
            }
            bubbleView = null
        }
    }
    private fun handleTouch(event: MotionEvent, params: WindowManager.LayoutParams): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                longPressTriggered = false
                downTime = System.currentTimeMillis()
                // 启动长按检测（800ms后触发）
                longPressHandler.postDelayed(longPressRunnable, 800)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()
                // 只有耗时超过250ms的移动才算拖拽，快速甩动不算（留给fling）
                val dur = System.currentTimeMillis() - downTime
                if ((Math.abs(dx) > 10 || Math.abs(dy) > 10) && dur > 250) {
                    isDragging = true
                    // 开始拖拽则取消长按
                    longPressHandler.removeCallbacks(longPressRunnable)
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager.updateViewLayout(petView, params)
                    // 同步移动气泡
                    bubbleView?.let {
                        val bp = bubbleParams ?: return@let
                        bp.x = params.x
                        bp.y = params.y - 70
                        windowManager.updateViewLayout(it, bp)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                // 甩动检测放最前：快速滑动（位移>50px且耗时<250ms）触发fling
                val upX = event.rawX
                val upY = event.rawY
                val dx = (upX - initialTouchX).toInt()
                val dy = (upY - initialTouchY).toInt()
                val dist = Math.sqrt((dx * dx + dy * dy).toDouble())
                val dur = System.currentTimeMillis() - downTime
                if (dist > 50 && dur < 250) {
                    webView.evaluateJavascript("window.petFling($dx, $dy);", null)
                    return true
                }
                if (isDragging || longPressTriggered) {
                    // 拖拽或长按过，不触发点击
                    return true
                }
                val now = System.currentTimeMillis()
                if (now - lastTapTime < 300) {
                    // 双击 -> 特殊动画（同时计入连戳计数）
                    lastTapTime = 0
                    pendingSingleTap = false
                    webView.evaluateJavascript("window.petTap();", null)
                    webView.evaluateJavascript("window.petDoubleTap();", null)
                } else {
                    // 可能是单击，延迟300ms确认不是双击
                    lastTapTime = now
                    pendingSingleTap = true
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (pendingSingleTap) {
                            pendingSingleTap = false
                            webView.evaluateJavascript("window.petReact();", null)
                        }
                    }, 300)
                }
            }
        }
        return true
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "pet_channel",
                "桌宠服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "pet_channel")
            .setContentTitle("AI桌宠")
            .setContentText("鲸鱼正在陪伴你~")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }


    private fun sendChatterNotification() {
        val lines = arrayOf(
            "主人，我在这里陪你哦~",
            "该休息一下啦，别太累",
            "我一直在看着你呢~",
            "咕噜咕噜，想你了",
            "记得喝水哦~",
            "主人加油，我支持你！"
        )
        val text = lines[Random.nextInt(lines.size)]
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, "pet_channel")
            .setContentTitle("🐳 AI桌宠")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(2, notification)
    }
    // ---- 喝水提醒（每2小时，气泡+通知） ----
    private fun sendDrinkReminder() {
        val lines = arrayOf(
            "主人，该喝水啦~ 记得补充水分哦",
            "咕噜咕噜... 主人喝口水吧",
            "两小时到啦，主人喝点水休息下~",
            "我帮你盯着呢，该喝水啦！"
        )
        val text = lines[Random.nextInt(lines.size)]
        // 气泡提醒
        bubbleHandler.post {
            showBubbleWindow(text)
        }
        // 通知提醒
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, "pet_channel")
            .setContentTitle("🐳 喝水提醒")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(3, notification)
    }
    // ---- 情绪引擎：轮询Supabase读取情绪值 ----
    private fun pollEmotion() {
        Thread {
            try {
                val url = URL("$supabaseUrl/rest/v1/pet_emotion?select=emotion,intensity,message&order=updated_at.desc&limit=1")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("apikey", supabaseKey)
                conn.setRequestProperty("Authorization", "Bearer $supabaseKey")
                conn.setRequestProperty("Accept", "application/json")
                val code = conn.responseCode
                if (code == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val sb = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) sb.append(line)
                    reader.close()
                    val body = sb.toString()
                    val arr = org.json.JSONArray(body)
                    if (arr.length() > 0) {
                        val obj = arr.getJSONObject(0)
                        val emotion = obj.optString("emotion", "neutral")
                        val intensity = obj.optInt("intensity", 5)
                        if (emotion != lastEmotion) {
                            lastEmotion = emotion
                            emotionHandler.post {
                                if (::webView.isInitialized) {
                                    webView.evaluateJavascript("window.petEmotion('$emotion', $intensity);", null)
                                }
                            }
                        }
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                // 网络异常静默，下轮重试
            }
        }.start()
    }
    override fun onDestroy() {
        super.onDestroy()
        bubbleHandler.removeCallbacks(bubbleHideRunnable)
        notifyHandler.removeCallbacks(notifyRunnable)
        emotionHandler.removeCallbacks(emotionRunnable)
        drinkHandler.removeCallbacks(drinkRunnable)
        appHandler.removeCallbacks(appRunnable)
        hideBubbleWindow()
        if (::petView.isInitialized) {
            windowManager.removeView(petView)
        }
    }
}