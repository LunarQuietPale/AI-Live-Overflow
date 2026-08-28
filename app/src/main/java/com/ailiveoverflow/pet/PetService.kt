package com.ailiveoverflow.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebSettings

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
        webView.loadUrl("file:///android_asset/pet.html")
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

    override fun onDestroy() {
        super.onDestroy()
        if (::petView.isInitialized) {
            windowManager.removeView(petView)
        }
    }
}