package com.example.ambilight

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/* ---------------- Bluetooth: LEDnetWF ---------------- */

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
object Ble {
    private val WRITE_UUID: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")

    class Dev(val address: String) {
        lateinit var gatt: BluetoothGatt
        @Volatile var ch: BluetoothGattCharacteristic? = null
        var seq = 0
    }

    private val map = ConcurrentHashMap<String, Dev>()
    @Volatile var brightness = 100
    @Volatile var rgbMode = false
    @Volatile var status: (String) -> Unit = {}
    private val main = Handler(Looper.getMainLooper())

    fun say(s: String) { main.post { status(s) } }
    fun connected(): Int = map.values.count { it.ch != null }

    fun scan(ctx: Context, onDone: (List<BluetoothDevice>) -> Unit) {
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { say("Включите Bluetooth и повторите"); return }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) { say("Bluetooth недоступен"); return }
        val found = LinkedHashMap<String, BluetoothDevice>()
        val cbk = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name
                if (name != null && name.startsWith("LEDnet", ignoreCase = true)) {
                    found[result.device.address] = result.device
                }
            }
        }
        scanner.startScan(cbk)
        say("Ищу ленту (7 секунд)…")
        main.postDelayed({
            try { scanner.stopScan(cbk) } catch (e: Exception) { }
            onDone(found.values.toList())
        }, 7000)
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            val d = map[g.device.address] ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!g.requestMtu(185)) g.discoverServices()
            } else {
                d.ch = null
                map.remove(g.device.address)
                try { g.close() } catch (e: Exception) { }
                say("Лента отключилась. Подключено: ${connected()}")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, st: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val d = map[g.device.address] ?: return
            for (s in g.services) {
                val c = s.getCharacteristic(WRITE_UUID) ?: continue
                val canNoResp = (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                c.writeType = if (canNoResp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                              else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                d.ch = c
            }
            if (d.ch == null) say("Не найден канал записи (это не LEDnetWF?)")
            else say("Подключено лент: ${connected()}. Можно запускать подсветку.")
        }
    }

    fun connect(ctx: Context, list: List<BluetoothDevice>) {
        disconnectAll()
        for (bd in list) {
            val d = Dev(bd.address)
            map[bd.address] = d
            d.gatt = bd.connectGatt(ctx.applicationContext, false, cb, BluetoothDevice.TRANSPORT_LE)
        }
        say("Подключаюсь, найдено лент: ${list.size}…")
    }

    fun disconnectAll() {
        for (d in map.values) {
            try { d.gatt.disconnect(); d.gatt.close() } catch (e: Exception) { }
        }
        map.clear()
    }

    private fun write(d: Dev, payload: IntArray) {
        val c = d.ch ?: return
        var sum = 0
        for (b in payload) sum = (sum + b) and 255
        val n = payload.size + 1
        d.seq = (d.seq + 1) and 255
        val full = IntArray(8 + payload.size + 1)
        val head = intArrayOf(0, d.seq, 0x80, 0, 0, n, n + 1, 0x0B)
        System.arraycopy(head, 0, full, 0, 8)
        System.arraycopy(payload, 0, full, 8, payload.size)
        full[full.size - 1] = sum
        c.value = ByteArray(full.size) { full[it].toByte() }
        d.gatt.writeCharacteristic(c)
    }

    fun sendOn() {
        val p = intArrayOf(0x3B, 0x23, 0, 0, 0, 0, 0, 0, 0, 0x32, 0, 0)
        for (d in map.values) write(d, p)
    }

    fun sendColor(r: Int, g: Int, b: Int) {
        val p: IntArray = if (rgbMode) {
            intArrayOf(0x31, r, g, b, 0, 0, 0xF0, 0x0F)
        } else {
            val hsv = FloatArray(3)
            Color.RGBToHSV(r, g, b, hsv)
            intArrayOf(
                0x3B, 0xA1,
                (hsv[0] / 2f).roundToInt(), (hsv[1] * 100f).roundToInt(), (hsv[2] * 100f).roundToInt(),
                0, 0, 0, 0, 0x32, 0, 0
            )
        }
        for (d in map.values) write(d, p)
    }
}

/* ---------------- Захват экрана ---------------- */

@SuppressLint("MissingPermission")
class CaptureService : Service() {
    companion object { @Volatile var running = false }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var projCb: MediaProjection.Callback? = null
    private var last = 0L
    private var cur: DoubleArray? = null
    private val sent = intArrayOf(-99, -99, -99)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
        }
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("amb", "Подсветка", NotificationManager.IMPORTANCE_LOW))
        val note = Notification.Builder(this, "amb")
            .setContentTitle("Подсветка экрана работает")
            .setContentText("Лента повторяет цвета экрана")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        startForeground(1, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data)
        projection = proj
        val th = HandlerThread("capture").also { it.start() }
        thread = th
        val handler = Handler(th.looper)

        val dm = resources.displayMetrics
        val w = 64
        val h = max(16, (64f * dm.heightPixels / dm.widthPixels).toInt())
        val rd = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = rd

        val pc = object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }
        projCb = pc
        proj.registerCallback(pc, handler)
        display = proj.createVirtualDisplay(
            "ambilight", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rd.surface, null, handler
        )
        rd.setOnImageAvailableListener({ r -> onFrame(r) }, handler)

        running = true
        Ble.sendOn()
        return START_NOT_STICKY
    }

    private fun onFrame(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        try {
            val now = SystemClock.uptimeMillis()
            if (now - last < 110) return
            last = now
            val p = img.planes[0]
            val buf = p.buffer
            val rs = p.rowStride
            val ps = p.pixelStride
            var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
            var y = 0
            while (y < img.height) {
                var x = 0
                while (x < img.width) {
                    val i = y * rs + x * ps
                    sr += buf.get(i).toInt() and 0xFF
                    sg += buf.get(i + 1).toInt() and 0xFF
                    sb += buf.get(i + 2).toInt() and 0xFF
                    n++
                    x += 2
                }
                y += 2
            }
            if (n == 0) return
            val ch = doubleArrayOf(sr.toDouble() / n, sg.toDouble() / n, sb.toDouble() / n)
            val avg = (ch[0] + ch[1] + ch[2]) / 3.0
            val k = Ble.brightness / 100.0
            val tg = DoubleArray(3) { (avg + (ch[it] - avg) * 1.5).coerceIn(0.0, 255.0) * k }
            val c = cur ?: tg.copyOf().also { cur = it }
            for (i in 0..2) c[i] += (tg[i] - c[i]) * 0.5
            val o = IntArray(3) { c[it].roundToInt() }
            if (max(abs(o[0] - sent[0]), max(abs(o[1] - sent[1]), abs(o[2] - sent[2]))) < 4) return
            sent[0] = o[0]; sent[1] = o[1]; sent[2] = o[2]
            Ble.sendColor(o[0], o[1], o[2])
        } finally {
            img.close()
        }
    }

    override fun onDestroy() {
        running = false
        try { reader?.setOnImageAvailableListener(null, null) } catch (e: Exception) { }
        try { display?.release() } catch (e: Exception) { }
        try { reader?.close() } catch (e: Exception) { }
        try {
            projCb?.let { projection?.unregisterCallback(it) }
            projection?.stop()
        } catch (e: Exception) { }
        thread?.quitSafely()
        super.onDestroy()
    }
}

/* ---------------- Экран приложения ---------------- */

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private var pending: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(t: String, color: String): Button = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.parseColor("#33261F"))
        background = GradientDrawable().apply {
            cornerRadius = 200f
            setColor(Color.parseColor(color))
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            topMargin = dp(12)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(44), dp(22), dp(22))
            setBackgroundColor(Color.parseColor("#F7EFE6"))
        }
        root.addView(TextView(this).apply {
            text = "💡 Ambilight"
            textSize = 28f
            setTextColor(Color.parseColor("#33261F"))
        })
        status = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#8A7A6F"))
            setPadding(0, dp(10), 0, dp(10))
            text = "Закройте приложение Zengge и нажмите «Найти ленту»"
        }
        root.addView(status)

        val connect = btn("🔍 Найти и подключить ленту", "#FFFFFF")
        toggle = btn("", "#FFC58A")
        root.addView(connect)
        root.addView(toggle)

        val lbl = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#33261F"))
            setPadding(0, dp(24), 0, 0)
            text = "Максимальная яркость: 100%"
        }
        val seek = SeekBar(this).apply { max = 100; progress = 100 }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                val v = max(1, p)
                Ble.brightness = v
                lbl.text = "Максимальная яркость: $v%"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(lbl)
        root.addView(seek)

        val alt = CheckBox(this).apply {
            text = "Запасная команда цвета (RGB), если цвета неверные"
            setTextColor(Color.parseColor("#8A7A6F"))
            setPadding(0, dp(16), 0, 0)
            setOnCheckedChangeListener { _, c -> Ble.rgbMode = c }
        }
        root.addView(alt)

        connect.setOnClickListener {
            withPerms {
                Ble.scan(this) { list ->
                    if (list.isEmpty()) Ble.say("Лента не найдена. Закройте Zengge, включите ленту и повторите.")
                    else Ble.connect(this, list)
                }
            }
        }
        toggle.setOnClickListener {
            if (CaptureService.running) {
                stopService(Intent(this, CaptureService::class.java))
                main.postDelayed({ refresh() }, 300)
            } else if (Ble.connected() == 0) {
                status.text = "Сначала подключите ленту"
            } else {
                withPerms {
                    val mpm = getSystemService(MediaProjectionManager::class.java)
                    startActivityForResult(mpm.createScreenCaptureIntent(), 100)
                }
            }
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        Ble.status = { status.text = it }
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun onDestroy() { Ble.status = {}; super.onDestroy() }

    private fun refresh() {
        toggle.text = if (CaptureService.running) "⏹ Остановить подсветку" else "▶ Запустить подсветку экрана"
    }

    private fun needed(): List<String> {
        val l = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            l.add(Manifest.permission.BLUETOOTH_SCAN)
            l.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            l.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun withPerms(then: () -> Unit) {
        val n = needed()
        if (n.isEmpty()) then() else { pending = then; requestPermissions(n.toTypedArray(), 1) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1) return
        val missing = needed().filter { it != Manifest.permission.POST_NOTIFICATIONS }
        val run = pending
        pending = null
        if (missing.isEmpty() && run != null) run() else status.text = "Нужны разрешения Bluetooth"
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100) {
            if (resultCode == RESULT_OK && data != null) {
                startForegroundService(
                    Intent(this, CaptureService::class.java).putExtra("code", resultCode).putExtra("data", data)
                )
                main.postDelayed({ refresh() }, 700)
            } else {
                status.text = "Доступ к экрану не дан"
            }
        }
    }
}
