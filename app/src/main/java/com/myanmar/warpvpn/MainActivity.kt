package com.myanmar.warpvpn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.wireguard.android.backend.GoBackend
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ConfigModel(
    val id: String,
    val name: String,
    val content: String,
    val endpoint: String,
    var isSelected: Boolean = false
)

class MainActivity : AppCompatActivity() {

    private val wgcfManager by lazy {
        WgcfManager { logMessage ->
            appendLog(logMessage)
        }
    }

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var btnMenu: ImageView
    private lateinit var btnConnectCard: MaterialCardView
    private lateinit var cardServer: MaterialCardView
    private lateinit var tvServerName: TextView
    private lateinit var imgPower: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvLogs: TextView
    private lateinit var cardLogs: MaterialCardView
    private lateinit var cardAdBanner: MaterialCardView
    private lateinit var btnGetAd: MaterialButton

    private lateinit var btnClearLogs: ImageView
    private lateinit var btnCopyLogs: ImageView

    private lateinit var cardEngineCf: MaterialCardView
    private lateinit var rbEngineCf: RadioButton
    private lateinit var cardEngineCustom: MaterialCardView
    private lateinit var rbEngineCustom: RadioButton

    private lateinit var switchDarkMode: SwitchMaterial
    private lateinit var switchLogs: SwitchMaterial
    private lateinit var switchPing: SwitchMaterial
    private lateinit var rgDns: RadioGroup
    private lateinit var rbDnsDefault: RadioButton
    private lateinit var rbDnsCloudflare: RadioButton
    private lateinit var rbDnsGoogle: RadioButton
    private lateinit var btnRestoreDefaults: MaterialButton
    private lateinit var tvTelegram: TextView

    // HWID and Split Tunneling Views
    private lateinit var cardHwid: MaterialCardView
    private lateinit var tvHwid: TextView
    private lateinit var switchSplitTunnel: SwitchMaterial

    // Views for Active Since and Connection Latency
    private lateinit var tvActiveSinceTime: TextView
    private lateinit var btnTestPing: TextView
    private lateinit var tvCfPing: TextView
    private lateinit var tvFbPing: TextView
    private lateinit var imgCfDot: ImageView
    private lateinit var imgFbDot: ImageView

    private lateinit var btnMainMenu: ImageView

    private var isConnected = false
    private var pingJob: Job? = null
    private var timerJob: Job? = null
    private var connectStartTime: Long = 0
    private var pendingConfigStr: String? = null
    private var expireTimerJob: Job? = null

    private val authManager by lazy { AuthManager(this) }
    private lateinit var cardExpireDate: MaterialCardView
    private lateinit var tvExpireDate: TextView

    private val backend by lazy { GoBackend(applicationContext) }
    private val tunnel = WgTunnel { newState ->
        if (newState == com.wireguard.android.backend.Tunnel.State.DOWN) {
            runOnUiThread {
                if (isConnected) {
                    resetUi()
                }
            }
        }
    }
    private val notificationHelper by lazy { NotificationHelper(this) }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            appendLog("Notification permission granted!")
        } else {
            appendLog("Notification permission denied!")
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            appendLog("VPN permission granted!")
            connectVpnWithPendingConfig()
        } else {
            appendLog("VPN permission denied!")
            resetUi()
            Toast.makeText(this, "VPN Permission is required!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean("DARK_MODE", true)

        if (isDark) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Drawer License Views
        cardExpireDate = findViewById(R.id.cardExpireDate)
        tvExpireDate = findViewById(R.id.tvExpireDate)

        cardExpireDate.setOnClickListener {
            showActivateLicenseDialog()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    showExitDialog()
                }
            }
        })

        // Initialize views
        drawerLayout = findViewById(R.id.drawerLayout)
        btnMenu = findViewById(R.id.btnMenu)
        btnMainMenu = findViewById(R.id.btnMainMenu)
        btnConnectCard = findViewById(R.id.btnConnectCard)
        cardServer = findViewById(R.id.cardServer)
        tvServerName = findViewById(R.id.tvServerName)
        imgPower = findViewById(R.id.imgPower)
        tvStatus = findViewById(R.id.tvStatus)
        tvLogs = findViewById(R.id.tvLogs)
        cardLogs = findViewById(R.id.cardLogs)
        cardAdBanner = findViewById(R.id.cardAdBanner)
        btnGetAd = findViewById(R.id.btnGetAd)

        btnClearLogs = findViewById(R.id.btnClearLogs)
        btnCopyLogs = findViewById(R.id.btnCopyLogs)

        cardEngineCf = findViewById(R.id.cardEngineCf)
        rbEngineCf = findViewById(R.id.rbEngineCf)
        cardEngineCustom = findViewById(R.id.cardEngineCustom)
        rbEngineCustom = findViewById(R.id.rbEngineCustom)

        switchDarkMode = findViewById(R.id.switchDarkMode)
        switchLogs = findViewById(R.id.switchLogs)
        switchPing = findViewById(R.id.switchPing)

        rgDns = findViewById(R.id.rgDns)
        rbDnsDefault = findViewById(R.id.rbDnsDefault)
        rbDnsCloudflare = findViewById(R.id.rbDnsCloudflare)
        rbDnsGoogle = findViewById(R.id.rbDnsGoogle)

        btnRestoreDefaults = findViewById(R.id.btnRestoreDefaults)
        tvTelegram = findViewById(R.id.tvTelegram)

        // Binding HWID and Split Tunnel
        cardHwid = findViewById(R.id.cardHwid)
        tvHwid = findViewById(R.id.tvHwid)
        switchSplitTunnel = findViewById(R.id.switchSplitTunnel)

        // Binding Active Since & Latency Views
        tvActiveSinceTime = findViewById(R.id.tvActiveSinceTime)
        btnTestPing = findViewById(R.id.btnTestPing)
        tvCfPing = findViewById(R.id.tvCfPing)
        tvFbPing = findViewById(R.id.tvFbPing)
        imgCfDot = findViewById(R.id.imgCfDot)
        imgFbDot = findViewById(R.id.imgFbDot)

        switchDarkMode.isChecked = isDark
        switchLogs.isChecked = prefs.getBoolean("SHOW_LOGS", true)
        switchPing.isChecked = prefs.getBoolean("AUTO_PING", false)
        switchSplitTunnel.isChecked = prefs.getBoolean("SPLIT_TUNNEL_ENABLED", false)

        val deviceHwid = getDeviceHwid()
        tvHwid.text = deviceHwid

        val savedEngine = prefs.getString("WARP_ENGINE", "CF_DIRECT")
        setEngineSelectionUI(savedEngine == "CF_DIRECT")

        setupListeners()

        when (prefs.getString("DNS_SETTING", "DEFAULT")) {
            "CLOUDFLARE" -> rbDnsCloudflare.isChecked = true
            "GOOGLE" -> rbDnsGoogle.isChecked = true
            else -> rbDnsDefault.isChecked = true
        }

        checkLicenseOnStartup()

        updateLogsAndAdVisibility(switchLogs.isChecked)

        updateActiveServerName()

        appendLog("Warp Tunnel app started")
        appendLog("Ready to connect...")

        checkNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE)
        if (::switchSplitTunnel.isInitialized) {
            switchSplitTunnel.isChecked = prefs.getBoolean("SPLIT_TUNNEL_ENABLED", false)
        }

        checkVpnStateAndResetIfNeeded()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_help -> {
                showHelpDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun checkLicenseOnStartup() {
        lifecycleScope.launch {
            try {
                val hwid = getDeviceHwid()
                val (isValid, message) = authManager.checkLicenseServer(hwid)

                if (!isValid) {
                    authManager.clearLicenseData()
                }
                
                updateExpireDateUI()
                
                if (!isValid && !isFinishing && !isDestroyed) {
                    showActivateLicenseDialog()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                appendLog("License startup error: ${e.message}")
            }
        }
    }

    private fun updateExpireDateUI() {
        expireTimerJob?.cancel()
        val expireDateMillis = authManager.getSavedExpireDate()
        val currentTime = System.currentTimeMillis()

        if (authManager.isLocalLicenseValid() && expireDateMillis > currentTime) {
            startExpireCountdown(expireDateMillis)
        } else {
            tvExpireDate.text = "Not Activated / Expired"
            tvExpireDate.setTextColor(Color.parseColor("#F87171"))

            authManager.clearLicenseData()

            if (isConnected) {
                disconnectVpn()
                appendLog("License not found. Disconnecting...")
            }
        }
    }

    private fun startExpireCountdown(expireDateMillis: Long) {
        expireTimerJob?.cancel()
        expireTimerJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                val currentTime = System.currentTimeMillis()
                val diff = expireDateMillis - currentTime
                
                if (diff > 0) {
                    val days = diff / (1000 * 60 * 60 * 24)
                    val hours = (diff / (1000 * 60 * 60)) % 24
                    val minutes = (diff / (1000 * 60)) % 60
                    val seconds = (diff / 1000) % 60
                    
                    val countdownText = if (days > 0) {
                        String.format(Locale.getDefault(), "%dd %02dh %02dm %02ds left", days, hours, minutes, seconds)
                    } else {
                        String.format(Locale.getDefault(), "%02dh %02dm %02ds left", hours, minutes, seconds)
                    }
                    
                    tvExpireDate.text = countdownText
                    tvExpireDate.setTextColor(Color.parseColor("#4ADE80"))
                } else {
                    tvExpireDate.text = "License Expired"
                    tvExpireDate.setTextColor(Color.parseColor("#F87171"))
                    authManager.clearLicenseData()

                    if (isConnected) {
                        disconnectVpn()
                        appendLog("⚠️ License expired. Connection terminated.")
                    }
                    break
                }
                delay(1000)
            }
        }
    }

    private fun showActivateLicenseDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_activate_license, null)
        val tvDialogHwid = dialogView.findViewById<TextView>(R.id.tvDialogHwid)
        val btnCopyHwid = dialogView.findViewById<MaterialButton>(R.id.btnCopyHwidDialog)
        val etLicenseKey = dialogView.findViewById<EditText>(R.id.etLicenseKey)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelDialog)
        val btnActivate = dialogView.findViewById<MaterialButton>(R.id.btnActivateDialog)

        val hwid = getDeviceHwid()
        tvDialogHwid.text = hwid

        val dialog = AlertDialog.Builder(this, R.style.DarkCustomDialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnCopyHwid.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("HWID", hwid)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "HWID Copied to Clipboard!", Toast.LENGTH_SHORT).show()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnActivate.setOnClickListener {
            val inputKey = etLicenseKey.text.toString().trim()
            if (inputKey.isNotEmpty()) {
                lifecycleScope.launch {
                    appendLog("Verifying serial key...")
                    val (success, message) = authManager.checkLicenseServer(hwid, inputKey)
                    if (success) {
                        Toast.makeText(this@MainActivity, "🎉 Activated Successfully!", Toast.LENGTH_SHORT).show()
                        appendLog("✅ License key activated!")
                        updateExpireDateUI()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(this@MainActivity, "❌ $message", Toast.LENGTH_LONG).show()
                        appendLog("❌ $message")
                    }
                }
            } else {
                Toast.makeText(this, "Please enter a valid Serial Key", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun showHelpDialog() {
        val helpMessage = """
            Welcome to WARP Tunnel!
            
            [1] Tap To Connect: Click the main power button to establish a secure Warp connection.
            
            [2] Engine Options:
            [3] Cloudflare Direct API: Connects directly through Cloudflare infrastructure.
            [4] Custom Backup API: Backup option if direct API is blocked.
            
            [5] Auto Clean IP:
            The app automatically scans 500+ Cloudflare IP endpoints in real-time to assign you the lowest latency & best performing IP for your ISP.
            
            [6] Split Tunneling:
            Exclude specific apps from using the VPN connection under the menu settings.
            
            [7] Ping Monitor:
            Live ping updates for Cloudflare and Facebook servers to verify actual connectivity.
        """.trimIndent()

        AlertDialog.Builder(this, R.style.DarkCustomDialog)
            .setTitle("❓ WARP Tunnel Help")
            .setMessage(helpMessage)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }

    private fun checkVpnStateAndResetIfNeeded() {
        if (isConnected) {
            lifecycleScope.launch(Dispatchers.IO) {
                val currentState = backend.getState(tunnel)
                if (currentState == com.wireguard.android.backend.Tunnel.State.DOWN) {
                    withContext(Dispatchers.Main) {
                        appendLog("⚠️ VPN disconnected in background.")
                        resetUi()
                    }
                }
            }
        }
    }

    private fun getDeviceHwid(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        if (androidId == null || androidId == "0000000000000000") {
            val deviceIdentifier = "35" +
            Build.BOARD.length % 10 +
            Build.BRAND.length % 10 +
            Build.CPU_ABI.length % 10 +
            Build.DEVICE.length % 10 +
            Build.DISPLAY.length % 10 +
            Build.HOST.length % 10 +
            Build.ID.length % 10 +
            Build.MANUFACTURER.length % 10 +
            Build.MODEL.length % 10 +
            Build.PRODUCT.length % 10 +
            Build.TAGS.length % 10 +
            Build.TYPE.length % 10 +
            Build.USER.length % 10
            return deviceIdentifier
        }
        return androidId
    }


    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun updateLogsAndAdVisibility(showLogs: Boolean) {
        if (showLogs) {
            cardLogs.visibility = View.VISIBLE
            cardAdBanner.visibility = View.GONE
        } else {
            cardLogs.visibility = View.GONE
            cardAdBanner.visibility = View.VISIBLE
        }
    }

    private fun openTelegramNkka() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/nkka404"))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot Open Telegram Link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupListeners() {
        btnMenu.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        btnMainMenu.setOnClickListener { view ->
            val popup = androidx.appcompat.widget.PopupMenu(this, view)
            popup.menuInflater.inflate(R.menu.main_menu, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_help -> {
                        showHelpDialog()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        btnConnectCard.setOnClickListener {
            val expireDateMillis = authManager.getSavedExpireDate()
            val currentTime = System.currentTimeMillis()
            
            if (authManager.isLocalLicenseValid() && expireDateMillis > currentTime) {
                if (isConnected) {
                    disconnectVpn()
                } else {
                    prepareAndConnectVpn()
                }
            } else {
                updateExpireDateUI()
                showActivateLicenseDialog()
                Toast.makeText(this, "Please activate your license first!", Toast.LENGTH_SHORT).show()
            }
        }

        cardServer.setOnClickListener {
            showSelectLocationBottomSheet()
        }

        cardHwid.setOnClickListener {
            showHwidDialog(getDeviceHwid())
        }

        // Ads Click Listeners
        cardAdBanner.setOnClickListener { openTelegramNkka() }
        btnGetAd.setOnClickListener { openTelegramNkka() }

        btnTestPing.setOnClickListener {
            if (isConnected) {
                lifecycleScope.launch(Dispatchers.IO) {
                    runSinglePing()
                }
            } else {
                Toast.makeText(this, "Please Connect To VPN first!", Toast.LENGTH_SHORT).show()
            }
        }

        switchSplitTunnel.setOnCheckedChangeListener { _, isChecked ->
            val prefs = getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("SPLIT_TUNNEL_ENABLED", isChecked).apply()
            if (isChecked) {
                appendLog("Split tunneling enabled")
                startActivity(Intent(this, AppListActivity::class.java))
            } else {
                appendLog("Split tunneling disabled")
            }
        }

        switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            val prefs = getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("DARK_MODE", isChecked).apply()
            if (isChecked) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }

        switchLogs.setOnCheckedChangeListener { _, isChecked ->
            val prefs = getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("SHOW_LOGS", isChecked).apply()
            updateLogsAndAdVisibility(isChecked)
        }

        switchPing.setOnCheckedChangeListener { _, isChecked ->
            val prefs = getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("AUTO_PING", isChecked).apply()
            if (isConnected) {
                startPingManager()
            }
        }

        rgDns.setOnCheckedChangeListener { _, checkedId ->
            val prefs = getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE)
            val dnsType = when (checkedId) {
                R.id.rbDnsCloudflare -> "CLOUDFLARE"
                R.id.rbDnsGoogle -> "GOOGLE"
                else -> "DEFAULT"
            }
            prefs.edit().putString("DNS_SETTING", dnsType).apply()
            appendLog("DNS mode set to: $dnsType")
        }

        btnClearLogs.setOnClickListener {
            tvLogs.text = "Logs cleared.\n"
            Toast.makeText(this, "Logs Cleared", Toast.LENGTH_SHORT).show()
        }

        btnCopyLogs.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Connection Logs", tvLogs.text.toString())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Logs Copied To Clipboard!", Toast.LENGTH_SHORT).show()
        }

        cardEngineCf.setOnClickListener {
            val prefs = getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE)
            prefs.edit().putString("WARP_ENGINE", "CF_DIRECT").apply()
            setEngineSelectionUI(true)
            appendLog("Engine set to cloudflare direct api")
        }

        cardEngineCustom.setOnClickListener {
            val prefs = getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE)
            prefs.edit().putString("WARP_ENGINE", "CUSTOM_API").apply()
            setEngineSelectionUI(false)
            appendLog("Engine set to custom backup api")
        }

        btnRestoreDefaults.setOnClickListener {
            showRestoreDefaultsDialog()
        }

        tvTelegram.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/premium_channel_404"))
            startActivity(intent)
        }
    }

    private fun showHwidDialog(hwid: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_hwid, null)
        val tvHwidValue = dialogView.findViewById<TextView>(R.id.tvHwidValue)
        val btnCopy = dialogView.findViewById<MaterialButton>(R.id.btnCopyHwid)
        val btnShare = dialogView.findViewById<MaterialButton>(R.id.btnShareHwid)

        tvHwidValue.text = hwid

        val dialog = AlertDialog.Builder(this, R.style.DarkCustomDialog)
            .setView(dialogView)
            .create()

        btnCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("HWID", hwid)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "HWID Copied To Clipboard!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        btnShare.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, hwid)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Device HWID"))
            dialog.dismiss()
        }

        dialog.show()

        val displayMetrics = resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.85).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun showRestoreDefaultsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_restore, null)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnRestoreCancel)
        val btnOk = dialogView.findViewById<MaterialButton>(R.id.btnRestoreOk)

        val dialog = AlertDialog.Builder(this, R.style.DarkCustomDialog)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnOk.setOnClickListener {
            val prefs = getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE)
            prefs.edit().clear().apply()

            switchDarkMode.isChecked = true
            switchLogs.isChecked = true
            switchPing.isChecked = false
            switchSplitTunnel.isChecked = false
            rbDnsDefault.isChecked = true
            setEngineSelectionUI(true)

            updateLogsAndAdVisibility(true)
            updateActiveServerName()
            appendLog("Restored all settings and configs to default.")
            Toast.makeText(this, "All settings restored to defaults!", Toast.LENGTH_SHORT).show()
            drawerLayout.closeDrawer(GravityCompat.START)

            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            dialog.dismiss()
        }

        dialog.show()

        val displayMetrics = resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.85).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun showExitDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_exit, null)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnDialogCancel)
        val btnMinimize = dialogView.findViewById<MaterialButton>(R.id.btnDialogMinimize)
        val btnExit = dialogView.findViewById<MaterialButton>(R.id.btnDialogExit)

        val dialog = AlertDialog.Builder(this, R.style.DarkCustomDialog)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnMinimize.setOnClickListener {
            dialog.dismiss()
            moveTaskToBack(true)
        }

        btnExit.setOnClickListener {
            if (isConnected) disconnectVpn()
            finishAffinity()
        }

        dialog.show()

        val displayMetrics = resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.90).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun setEngineSelectionUI(isCfDirect: Boolean) {
        if (isCfDirect) {
            rbEngineCf.isChecked = true
            rbEngineCustom.isChecked = false
            cardEngineCf.strokeColor = Color.parseColor("#38BDF8")
            cardEngineCf.strokeWidth = 4
            cardEngineCustom.strokeColor = Color.parseColor("#334155")
            cardEngineCustom.strokeWidth = 2
        } else {
            rbEngineCf.isChecked = false
            rbEngineCustom.isChecked = true
            cardEngineCf.strokeColor = Color.parseColor("#334155")
            cardEngineCf.strokeWidth = 2
            cardEngineCustom.strokeColor = Color.parseColor("#38BDF8")
            cardEngineCustom.strokeWidth = 4
        }
    }

    private fun updateActiveServerName() {
        val selected = getSelectedConfig()
        if (selected != null) {
            tvServerName.text = selected.name
        } else {
            tvServerName.text = "Warp Auto Clean IP"
        }
    }

    private fun maskEndpoint(endpoint: String): String {
        return try {
            val parts = endpoint.split(":")
            if (parts.size == 2) {
                val ipParts = parts[0].split(".")
                if (ipParts.size == 4) {
                    "${ipParts[0]}.${ipParts[1]}.**.** : ***"
                } else {
                    endpoint
                }
            } else {
                endpoint
            }
        } catch (e: Exception) {
            endpoint
        }
    }

    private fun showSelectLocationBottomSheet() {
        val bottomSheet = BottomSheetDialog(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_select_location, null)
        bottomSheet.setContentView(dialogView)

        val btnAddConfig = dialogView.findViewById<MaterialCardView>(R.id.btnAddConfig)
        val tvEmptyState = dialogView.findViewById<TextView>(R.id.tvEmptyState)
        val rvConfigs = dialogView.findViewById<RecyclerView>(R.id.rvConfigs)

        rvConfigs.layoutManager = LinearLayoutManager(this)

        fun refreshList() {
            val configList = getAllConfigs()
            if (configList.isEmpty()) {
                tvEmptyState.visibility = View.VISIBLE
                rvConfigs.visibility = View.GONE
            } else {
                tvEmptyState.visibility = View.GONE
                rvConfigs.visibility = View.VISIBLE
                rvConfigs.adapter = ConfigAdapter(configList, { selectedConfig ->
                    setSelectedConfig(selectedConfig.id)
                    updateActiveServerName()
                    bottomSheet.dismiss()
                }, { deleteConfig ->
                    if (isConnected) {
                        Toast.makeText(this, "Please Disconnect VPN First!", Toast.LENGTH_SHORT).show()
                    } else {
                        deleteConfigById(deleteConfig.id)
                        appendLog("Deleted config: ${deleteConfig.name}")
                        refreshList()
                        updateActiveServerName()
                    }
                })
            }
        }

        refreshList()

        btnAddConfig.setOnClickListener {
            bottomSheet.dismiss()
            showImportConfigDialog()
        }

        bottomSheet.show()
    }

    private fun showImportConfigDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_server, null)
        val etConfigInput = dialogView.findViewById<EditText>(R.id.etConfigInput)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)
        val btnImport = dialogView.findViewById<MaterialButton>(R.id.btnImport)

        val dialog = AlertDialog.Builder(this, R.style.DarkCustomDialog)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnImport.setOnClickListener {
            val inputText = etConfigInput.text.toString().trim()
            if (inputText.isNotEmpty()) {
                try {
                    var parsedConfig = inputText
                    if (inputText.startsWith("wireguard://", ignoreCase = true)) {
                        parsedConfig = parseWireGuardUri(inputText)
                    } else {
                        Config.parse(ByteArrayInputStream(parsedConfig.toByteArray()))
                    }

                    val newId = System.currentTimeMillis().toString()
                    val name = "Imported Server #${getAllConfigs().size + 1}"
                    val rawEndpoint = extractEndpoint(parsedConfig)
                    val maskedEndpoint = maskEndpoint(rawEndpoint)

                    saveNewConfig(ConfigModel(newId, name, parsedConfig, maskedEndpoint, true))

                    appendLog("Config imported successfully!")
                    Toast.makeText(this@MainActivity, "Config imported successfully!", Toast.LENGTH_SHORT).show()
                    updateActiveServerName()
                    dialog.dismiss()

                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this@MainActivity, "Invalid config format: ${e.message}", Toast.LENGTH_LONG).show()
                    appendLog("Import error: ${e.message}")
                }
            } else {
                Toast.makeText(this, "Please paste valid config!", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun parseWireGuardUri(uriString: String): String {
        try {
            val cleanUri = uriString.replace("wireguard://", "")

            val atIndex = cleanUri.indexOf('@')
            if (atIndex == -1) throw Exception("Missing @")

            val privateKey = cleanUri.substring(0, atIndex)
            val rest = cleanUri.substring(atIndex + 1)

            val questionIndex = rest.indexOf('?')
            if (questionIndex == -1) throw Exception("Missing ?")

            val endpointPart = rest.substring(0, questionIndex)
            val queryPart = rest.substring(questionIndex + 1)

            val endpointParts = endpointPart.split(':')
            if (endpointParts.size != 2) throw Exception("Invalid endpoint")
            val endpointHost = endpointParts[0]
            val endpointPort = endpointParts[1]

            val params = mutableMapOf<String, String>()
            queryPart.split('&').forEach { param ->
                val parts = param.split('=')
                if (parts.size == 2) {
                    val key = parts[0]
                    val value = URLDecoder.decode(parts[1], "UTF-8")
                    params[key] = value
                }
            }

            val address = params["address"] ?: throw Exception("Missing address")
            val publicKey = params["publickey"] ?: throw Exception("Missing publickey")
            val mtu = params["mtu"] ?: "1280"

            return buildRawConfig(
                privateKey = URLDecoder.decode(privateKey, "UTF-8"),
                endpoint = "$endpointHost:$endpointPort",
                address = address,
                publicKey = publicKey,
                mtu = mtu
            )

        } catch (e: Exception) {
            throw Exception("Failed to parse WireGuard URI: ${e.message}")
        }
    }

    private fun buildRawConfig(
        privateKey: String,
        endpoint: String,
        address: String,
        publicKey: String,
        mtu: String
    ): String {
        val formattedAddress = if (address.contains(",") && !address.contains(", ")) {
            address.replace(",", ", ")
        } else {
            address
        }

        return """
            [Interface]
            PrivateKey = $privateKey
            Address = $formattedAddress
            DNS = 1.1.1.1, 1.0.0.1
            MTU = $mtu

            [Peer]
            PublicKey = $publicKey
            Endpoint = $endpoint
            AllowedIPs = 0.0.0.0/0, ::/0
        """.trimIndent()
    }

    private fun extractEndpoint(configStr: String): String {
        val match = Regex("Endpoint\\s*=\\s*(\\S+)").find(configStr)
        return match?.groupValues?.get(1) ?: "162.159.192.1:500"
    }

    private fun applyCustomDnsToConfig(rawConfig: String): String {
        val prefs = getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE)
        val dnsSetting = prefs.getString("DNS_SETTING", "DEFAULT")

        val targetDns = when (dnsSetting) {
            "CLOUDFLARE" -> "1.1.1.1, 1.0.0.1"
            "GOOGLE" -> "8.8.8.8, 8.8.4.4"
            else -> return rawConfig
        }

        return if (rawConfig.contains("DNS =", ignoreCase = true)) {
            rawConfig.replace(Regex("DNS\\s*=\\s*[^\\n]+", RegexOption.IGNORE_CASE), "DNS = $targetDns")
        } else {
            rawConfig.replace("[Interface]", "[Interface]\nDNS = $targetDns")
        }
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            tvLogs.append("[$timestamp] $message\n")

            tvLogs.post {
                val scrollAmount = tvLogs.layout?.getLineTop(tvLogs.lineCount) ?: 0
                tvLogs.scrollTo(0, maxOf(0, scrollAmount - tvLogs.height))
            }
        }
    }

    private fun prepareAndConnectVpn() {
        tvStatus.text = "VERIFYING LICENSE..."
        btnConnectCard.setStrokeColor(Color.parseColor("#F59E0B"))

        lifecycleScope.launch(Dispatchers.IO) {
            val hwid = getDeviceHwid()
            val (isValid, message) = authManager.checkLicenseServer(hwid)

            withContext(Dispatchers.Main) {

                if (!isValid) {
                    authManager.clearLicenseData()
                }
                
                updateExpireDateUI()

                if (!isValid) {
                    Toast.makeText(this@MainActivity, "❌ $message", Toast.LENGTH_LONG).show()
                    appendLog("❌ $message")
                    resetUi()
                    showActivateLicenseDialog()
                    return@withContext
                }

                appendLog("✅ License active. Proceeding with vpn connection...")
                startActualVpnConnection()
            }
        }
    }

    private fun startActualVpnConnection() {
        tvStatus.text = "CONNECTING..."
        btnConnectCard.setStrokeColor(Color.parseColor("#F59E0B"))
        appendLog("Preparing vpn connection...")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var selectedModel = getSelectedConfig()
                var configStr: String

                if (selectedModel == null) {
                    val prefs = getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE)
                    val engineMode = prefs.getString("WARP_ENGINE", "CF_DIRECT") ?: "CF_DIRECT"

                    appendLog("No config found. Requesting new config via Engine: $engineMode...")

                    try {
                        configStr = wgcfManager.registerAndGetConfig(engineMode)
                        appendLog("Config received successfully!")
                    } catch (e: Exception) {
                        appendLog("Error: ${e.message}")
                        val fallbackEngine = if (engineMode == "CF_DIRECT") "CUSTOM_API" else "CF_DIRECT"
                        appendLog("Trying fallback engine: $fallbackEngine")
                        configStr = wgcfManager.registerAndGetConfig(fallbackEngine)
                    }

                    val rawEndpoint = extractEndpoint(configStr)
                    val maskedEndpoint = maskEndpoint(rawEndpoint)

                    val newModel = ConfigModel(
                        "warp_${System.currentTimeMillis()}",
                        "Warp Auto Clean IP",
                        configStr,
                        maskedEndpoint,
                        true
                    )
                    saveNewConfig(newModel)
                    appendLog("New warp config saved!")
                } else {
                    configStr = selectedModel.content
                    appendLog("Using active config [${selectedModel.name}]...")
                }

                configStr = applyCustomDnsToConfig(configStr)

                try {
                    Config.parse(ByteArrayInputStream(configStr.toByteArray()))
                    appendLog("✅ Config validation successful")
                } catch (e: Exception) {
                    appendLog("❌ Config validation failed: ${e.message}")
                    throw Exception("Invalid config: ${e.message}")
                }

                pendingConfigStr = configStr

                withContext(Dispatchers.Main) {
                    val intent = VpnService.prepare(this@MainActivity)
                    if (intent != null) {
                        appendLog("Requesting vpn permission...")
                        vpnPermissionLauncher.launch(intent)
                    } else {
                        appendLog("VPN permission already granted.")
                        connectVpnWithPendingConfig()
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("Error: ${e.message}")
                    e.printStackTrace()
                    Toast.makeText(this@MainActivity, "Connection failed!", Toast.LENGTH_SHORT).show()
                    resetUi()
                }
            }
        }
    }

    private fun connectVpnWithPendingConfig() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val configStr = pendingConfigStr ?: wgcfManager.registerAndGetConfig(engineMode = "CF_DIRECT")

                appendLog("Building tunnel session...")
                val parsedConfig = Config.parse(ByteArrayInputStream(configStr.toByteArray()))

                val prefs = getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE)
                val isSplitEnabled = prefs.getBoolean("SPLIT_TUNNEL_ENABLED", false)
                val excludedApps = prefs.getStringSet("EXCLUDED_APPS", emptySet()) ?: emptySet()

                val origInterface = parsedConfig.`interface`

                val interfaceBuilder = com.wireguard.config.Interface.Builder()
                    .setKeyPair(origInterface.keyPair)
                    .addAddresses(origInterface.addresses)
                    .addDnsServers(origInterface.dnsServers)

                origInterface.listenPort.ifPresent { interfaceBuilder.setListenPort(it) }
                origInterface.mtu.ifPresent { interfaceBuilder.setMtu(it) }

                if (isSplitEnabled && excludedApps.isNotEmpty()) {
                    interfaceBuilder.excludeApplications(excludedApps)
                    appendLog("Split tunneling active: Excluded ${excludedApps.size} apps.")
                }

                val finalWgConfig = Config.Builder()
                    .setInterface(interfaceBuilder.build())
                    .addPeers(parsedConfig.peers)
                    .build()

                backend.setState(tunnel, com.wireguard.android.backend.Tunnel.State.UP, finalWgConfig)

                withContext(Dispatchers.Main) {
                    isConnected = true
                    connectStartTime = System.currentTimeMillis()

                    tvStatus.text = "CONNECTED"
                    tvStatus.setTextColor(Color.parseColor("#4ADE80"))
                    btnConnectCard.setStrokeColor(Color.parseColor("#4ADE80"))
                    imgPower.setColorFilter(Color.parseColor("#4ADE80"))

                    Toast.makeText(this@MainActivity, "Warp Tunnel Connected!", Toast.LENGTH_SHORT).show()
                    appendLog("✅ Connected to Warp Tunnel!")

                    notificationHelper.updateNotification("Measuring...")

                    startActiveSinceTimer()
                    startPingManager()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("Connection error: ${e.message}")
                    e.printStackTrace()
                    Toast.makeText(this@MainActivity, "Connection failed: ${e.message}", Toast.LENGTH_LONG).show()
                    resetUi()
                }
            }
        }
    }

    private fun disconnectVpn() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                stopPingManager()
                stopActiveSinceTimer()

                backend.setState(tunnel, com.wireguard.android.backend.Tunnel.State.DOWN, null)

                withContext(Dispatchers.Main) {
                    appendLog("Disconnected from Warp Tunnel.")
                    Toast.makeText(this@MainActivity, "Warp Tunnel Disconnected", Toast.LENGTH_SHORT).show()
                    resetUi()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("Disconnect error: ${e.message}")
                    resetUi()
                }
            }
        }
    }

    private fun startActiveSinceTimer() {
        stopActiveSinceTimer()
        timerJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive && isConnected) {
                val elapsedSeconds = (System.currentTimeMillis() - connectStartTime) / 1000
                tvActiveSinceTime.text = formatElapsedTime(elapsedSeconds)
                delay(1000)
            }
        }
    }

    private fun stopActiveSinceTimer() {
        timerJob?.cancel()
        timerJob = null
        runOnUiThread {
            tvActiveSinceTime.text = "Not Connected"
        }
    }

    private fun formatElapsedTime(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m ${secs}s ago"
            minutes > 0 -> "${minutes}m ${secs}s ago"
            else -> "$seconds seconds ago"
        }
    }

    private fun startPingManager() {
        stopPingManager()
        pingJob = lifecycleScope.launch(Dispatchers.IO) {
            val isAutoPing = switchPing.isChecked
            if (isAutoPing) {
                while (isActive && isConnected) {
                    runSinglePing()
                    delay(30000)
                }
            } else {
                runSinglePing()
            }
        }
    }

    private fun stopPingManager() {
        pingJob?.cancel()
        pingJob = null
    }

    private fun animateDot(view: View, startAnimation: Boolean) {
        if (startAnimation) {
            if (view.animation == null) {
                val anim = android.view.animation.AlphaAnimation(0.3f, 1.0f).apply {
                    duration = 600
                    repeatMode = android.view.animation.Animation.REVERSE
                    repeatCount = android.view.animation.Animation.INFINITE
                }
                view.startAnimation(anim)
            }
        } else {
            view.clearAnimation()
        }
    }

    private suspend fun runSinglePing() = withContext(Dispatchers.IO) {
        try {
            if (!isConnected) return@withContext

            // Cloudflare Ping
            val cfStartTime = System.currentTimeMillis()
            val cfAddress = InetAddress.getByName("1.1.1.1")
            val cfReachable = cfAddress.isReachable(2500)
            val cfPingTime = System.currentTimeMillis() - cfStartTime

            // Facebook Ping
            val fbStartTime = System.currentTimeMillis()
            val fbAddress = InetAddress.getByName("h.facebook.com")
            val fbReachable = fbAddress.isReachable(2500)
            val fbPingTime = System.currentTimeMillis() - fbStartTime

            val cfResult = if (cfReachable) "${cfPingTime}ms" else "Timeout"
            val fbResult = if (fbReachable) "${fbPingTime}ms" else "Timeout"

            val logMessage = "🏓 Ping -> CF: $cfResult | FB: $fbResult"
            val notiMessage = "CF: $cfResult | FB: $fbResult"

            appendLog(logMessage)

            withContext(Dispatchers.Main) {
                if (isConnected) {
                    tvCfPing.text = cfResult
                    tvFbPing.text = fbResult

                    // Cloudflare Dot
                    if (cfReachable) {
                        imgCfDot.setColorFilter(Color.parseColor("#00FF00"))
                        animateDot(imgCfDot, true)
                    } else {
                        imgCfDot.setColorFilter(Color.parseColor("#64748B"))
                        animateDot(imgCfDot, false)
                    }

                    // Facebook Dot
                    if (fbReachable) {
                        imgFbDot.setColorFilter(Color.parseColor("#00FF00"))
                        animateDot(imgFbDot, true)
                    } else {
                        imgFbDot.setColorFilter(Color.parseColor("#64748B"))
                        animateDot(imgFbDot, false)
                    }

                    notificationHelper.updateNotification(notiMessage)
                }
            }

        } catch (e: Exception) {
            if (isActive) {
                appendLog("Ping error: ${e.localizedMessage}")
                withContext(Dispatchers.Main) {
                    tvCfPing.text = "N/A"
                    tvFbPing.text = "N/A"

                    imgCfDot.setColorFilter(Color.parseColor("#64748B"))
                    imgFbDot.setColorFilter(Color.parseColor("#64748B"))
                    animateDot(imgCfDot, false)
                    animateDot(imgFbDot, false)

                    notificationHelper.updateNotification("Ping error")
                }
            }
        }
    }

    private fun resetUi() {
        runOnUiThread {
            stopPingManager()
            stopActiveSinceTimer()

            isConnected = false
            tvStatus.text = "TAP TO CONNECT"
            tvStatus.setTextColor(Color.parseColor("#94A3B8"))
            btnConnectCard.setStrokeColor(Color.parseColor("#334155"))
            imgPower.setColorFilter(Color.parseColor("#94A3B8"))

            tvCfPing.text = "N/A"
            tvFbPing.text = "N/A"
            imgCfDot.setColorFilter(Color.parseColor("#64748B"))
            imgFbDot.setColorFilter(Color.parseColor("#64748B"))

            animateDot(imgCfDot, false)
            animateDot(imgFbDot, false)

            notificationHelper.cancelNotification()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        expireTimerJob?.cancel()
        stopPingManager()
        stopActiveSinceTimer()
    }

    // ==================== Config Management ====================

    private fun getAllConfigs(): List<ConfigModel> {
        val prefs = getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("CONFIG_LIST_JSON", "[]")
        val list = mutableListOf<ConfigModel>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    ConfigModel(
                        obj.getString("id"),
                        obj.getString("name"),
                        obj.getString("content"),
                        obj.getString("endpoint"),
                        obj.optBoolean("isSelected", false)
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (list.isNotEmpty() && list.none { it.isSelected }) {
            list[0].isSelected = true
        }
        return list
    }

    private fun getSelectedConfig(): ConfigModel? {
        val list = getAllConfigs()
        return list.find { it.isSelected } ?: list.firstOrNull()
    }

    private fun setSelectedConfig(id: String) {
        val list = getAllConfigs()
        list.forEach { it.isSelected = (it.id == id) }
        saveConfigList(list)
    }

    private fun saveNewConfig(model: ConfigModel) {
        val list = getAllConfigs().toMutableList()
        list.forEach { it.isSelected = false }
        model.isSelected = true
        list.add(0, model)
        saveConfigList(list)
    }

    private fun deleteConfigById(id: String) {
        val list = getAllConfigs().filter { it.id != id }
        if (list.isNotEmpty() && list.none { it.isSelected }) {
            list[0].isSelected = true
        }
        saveConfigList(list)
    }

    private fun saveConfigList(list: List<ConfigModel>) {
        val array = JSONArray()
        list.forEach {
            val obj = JSONObject()
            obj.put("id", it.id)
            obj.put("name", it.name)
            obj.put("content", it.content)
            obj.put("endpoint", it.endpoint)
            obj.put("isSelected", it.isSelected)
            array.put(obj)
        }
        val prefs = getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE)
        prefs.edit().putString("CONFIG_LIST_JSON", array.toString()).apply()
    }

    // ==================== Config Adapter ====================

    class ConfigAdapter(
        private val list: List<ConfigModel>,
        private val onItemClick: (ConfigModel) -> Unit,
        private val onDeleteClick: (ConfigModel) -> Unit
    ) : RecyclerView.Adapter<ConfigAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvName)
            val tvEndpoint: TextView = view.findViewById(R.id.tvEndpoint)
            val btnDelete: ImageView = view.findViewById(R.id.btnDelete)
            val imgCheck: ImageView = view.findViewById(R.id.imgCheck)
            val cardItem: MaterialCardView = view.findViewById(R.id.cardItem)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_config, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.tvName.text = item.name
            holder.tvEndpoint.text = item.endpoint

            if (item.isSelected) {
                holder.cardItem.strokeColor = Color.parseColor("#22C55E")
                holder.cardItem.strokeWidth = 4
                holder.imgCheck.visibility = View.VISIBLE
            } else {
                holder.cardItem.strokeColor = Color.parseColor("#334155")
                holder.cardItem.strokeWidth = 2
                holder.imgCheck.visibility = View.INVISIBLE
            }

            holder.cardItem.setOnClickListener { onItemClick(item) }
            holder.btnDelete.setOnClickListener { onDeleteClick(item) }
        }

        override fun getItemCount(): Int = list.size
    }

    // ==================== WgTunnel ====================

    class WgTunnel(private val onStateChangedListener: ((com.wireguard.android.backend.Tunnel.State) -> Unit)? = null) : com.wireguard.android.backend.Tunnel {
        override fun getName(): String = "WARPTunnel"

        override fun onStateChange(newState: com.wireguard.android.backend.Tunnel.State) {
            onStateChangedListener?.invoke(newState)
        }
    }
}
