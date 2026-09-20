package com.ffsagent

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var packageEdit: EditText
    private lateinit var selectedAppLabel: TextView
    private lateinit var intervalEdit: EditText
    private lateinit var retailerLocationEdit: EditText
    private lateinit var receiverMobileEdit: EditText
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var startButton: Button

    private val green = Color.rgb(0, 154, 73)
    private val darkGreen = Color.rgb(0, 96, 52)
    private val navy = Color.rgb(15, 35, 58)
    private val muted = Color.rgb(82, 98, 116)
    private val page = Color.rgb(247, 250, 252)

    private val agentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != FfsAccessibilityService.ACTION_STATUS) return
            val state = intent.getStringExtra(FfsAccessibilityService.EXTRA_STATUS).orEmpty()
            val log = intent.getStringExtra(FfsAccessibilityService.EXTRA_LOG).orEmpty()
            status.text = state
            updateStartButton()
            logView.text = ("${logView.text}\n$log").trim().takeLast(10000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        window.statusBarColor = green
        window.navigationBarColor = darkGreen
        if (Build.VERSION.SDK_INT >= 23) window.decorView.systemUiVisibility = 0
        buildUi()
        status.text = prefs.getString("agent_status", "READY") ?: "READY"
        updateStartButton()
        logView.text = prefs.getString("agent_log", "No agent activity yet.\n\nEnter your details, enable Accessibility once, then tap START AGENT. OTP remains manual.")
        val filter = IntentFilter(FfsAccessibilityService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(agentReceiver, filter, Context.RECEIVER_NOT_EXPORTED) else {
            @Suppress("DEPRECATION") registerReceiver(agentReceiver, filter)
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) {
            status.text = prefs.getString("agent_status", "READY") ?: "READY"
            updateStartButton()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::intervalEdit.isInitialized && ::retailerLocationEdit.isInitialized && ::receiverMobileEdit.isInitialized) {
            persistConfig()
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(agentReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(page) }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }
        scroll.addView(content)

        // Header
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(12)) }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ffs_logo)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, LinearLayout.LayoutParams(dp(58), dp(58)))
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        titleBox.addView(tv("FFS Agent", 23f, navy, true))
        titleBox.addView(tv("Automate. Save Time. Get Urea Faster.", 12.5f, muted, false))
        header.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(tv("⚙", 27f, Color.rgb(57, 74, 91), false).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6),0,0,0)
            contentDescription = "Accessibility Settings"
            isClickable = true
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }, LinearLayout.LayoutParams(dp(42), dp(50)))
        content.addView(header)

        // Ready banner
        val ready = roundedCard(Color.rgb(213, 248, 228), dp(16), dp(14))
        val rr = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        rr.addView(circleIcon("✓", green, 42), LinearLayout.LayoutParams(dp(42), dp(42)))
        val rt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12),0,0,0) }
        rt.addView(tv("Agent Ready", 17f, navy, true))
        rt.addView(tv("Configure details below and start automation", 12.5f, Color.rgb(41,85,63), false))
        rr.addView(rt, LinearLayout.LayoutParams(0,-2,1f))
        ready.addView(rr)
        content.addView(ready, margin(0,0,0,12))

        // FFS application card
        val appCard = roundedCard(Color.WHITE, dp(16), dp(16))
        val appRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        appRow.addView(circleIcon("▦", Color.rgb(38,105,235), 42), LinearLayout.LayoutParams(dp(42),dp(42)))
        val appInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12),0,dp(8),0) }
        appInfo.addView(tv("FFS Application", 16f, navy, true))
        selectedAppLabel = tv("", 12.5f, muted, false)
        appInfo.addView(selectedAppLabel)
        appRow.addView(appInfo, LinearLayout.LayoutParams(0,-2,1f))
        appRow.addView(button("SELECT APP", false) { showAppPicker() }, LinearLayout.LayoutParams(dp(106), dp(42)))
        appCard.addView(appRow)
        packageEdit = EditText(this).apply { setSingleLine(true); setText(prefs.getString("target_package", "com.agristack.fsas")); visibility = View.GONE }
        appCard.addView(packageEdit)
        content.addView(appCard, margin(0,0,0,18))
        updateSelectedAppLabel()

        content.addView(sectionHeader("⚙", "Automation Settings"), margin(0,0,0,8))
        val settings = roundedCard(Color.WHITE, dp(18), dp(15))
        settings.addView(fieldLabel("◷", "Agent Re-check Interval (ms)"))
        intervalEdit = edit(prefs.getLong("poll_ms", 100L).toString(), InputType.TYPE_CLASS_NUMBER)
        settings.addView(intervalEdit, margin(0,4,0,3))
        settings.addView(helper("How often to search and check (default: 100 ms)"), margin(0,0,0,10))

        settings.addView(fieldLabel("●", "Retailer / PACS Location"))
        retailerLocationEdit = edit(prefs.getString("retailer_location", "Korlagudem") ?: "Korlagudem", InputType.TYPE_CLASS_TEXT)
        settings.addView(retailerLocationEdit, margin(0,4,0,3))
        settings.addView(helper("Enter retailer or PACS name (e.g. Korlagudem)"), margin(0,0,0,10))

        settings.addView(fieldLabel("☎", "Receiver Mobile Number"))
        receiverMobileEdit = edit(prefs.getString("receiver_mobile", "") ?: "", InputType.TYPE_CLASS_PHONE)
        settings.addView(receiverMobileEdit, margin(0,4,0,0))
        content.addView(settings, margin(0,0,0,14))

        // Start button
        startButton = button("▶  START AGENT", true) { toggleAgent() }
        startButton.setTextSize(17f)
        content.addView(startButton, margin(0,0,0,12))
        val startSub = tv("Login and complete Urea booking automatically", 12f, Color.WHITE, false)
        startSub.visibility = View.GONE

        // Settings / accessibility
        // Configuration is persisted automatically; there is no separate Load step.
        val settingsAction = actionButton("⚙", "Settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        content.addView(settingsAction, LinearLayout.LayoutParams(-1, dp(62)).apply {
            setMargins(0, 0, 0, dp(12))
        })

        // Persist every user change automatically so the next launch uses the latest values.
        val autoSaveWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (::intervalEdit.isInitialized && ::retailerLocationEdit.isInitialized && ::receiverMobileEdit.isInitialized) {
                    persistConfig()
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        intervalEdit.addTextChangedListener(autoSaveWatcher)
        retailerLocationEdit.addTextChangedListener(autoSaveWatcher)
        receiverMobileEdit.addTextChangedListener(autoSaveWatcher)

        // Info banner
        val info = roundedCard(Color.rgb(224, 239, 255), dp(14), dp(12))
        val ir = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        ir.addView(tv("ⓘ", 24f, Color.rgb(31,105,230), true), LinearLayout.LayoutParams(dp(34),-2))
        ir.addView(tv("Make sure FFS app is installed and you have\ncompleted manual OTP login.", 12f, Color.rgb(36,72,111), false))
        info.addView(ir)
        content.addView(info, margin(0,0,0,14))

        // Live status card
        val liveHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2),0,dp(2),dp(8)) }
        liveHeader.addView(tv("●", 18f, green, true), LinearLayout.LayoutParams(dp(25),-2))
        liveHeader.addView(tv("Agent Status", 18f, navy, true), LinearLayout.LayoutParams(0,-2,1f))
        // The main START AGENT button is the single Start/Stop toggle.
        content.addView(liveHeader)

        val statusCard = roundedCard(Color.WHITE, dp(16), dp(14))
        status = tv("READY", 18f, navy, true)
        statusCard.addView(status)
        statusCard.addView(tv("Please wait while the agent completes the process...", 12f, muted, false), margin(0,3,0,0))
        content.addView(statusCard, margin(0,0,0,12))

        val progress = roundedCard(Color.WHITE, dp(16), dp(12))
        progress.addView(progressRow())
        content.addView(progress, margin(0,0,0,12))

        content.addView(sectionHeader("↗", "Live Agent Log"), margin(0,0,0,8))
        val logCard = roundedCard(Color.WHITE, dp(16), dp(12))
        val logTop = LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        logTop.addView(tv("", 1f, Color.TRANSPARENT, false), LinearLayout.LayoutParams(0,1,1f))
        logTop.addView(button("Clear", false) { logView.text = "" }, LinearLayout.LayoutParams(dp(72),dp(38)))
        logCard.addView(logTop)
        logView = tv("", 12f, muted, false).apply {
            setPadding(dp(8),dp(10),dp(8),dp(4)); setMinHeight(dp(150))
        }
        logCard.addView(logView)
        content.addView(logCard, margin(0,0,0,14))

        val success = roundedCard(Color.rgb(218, 249, 231), dp(16), dp(14))
        val sr = LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        sr.addView(circleIcon("✓", green, 46), LinearLayout.LayoutParams(dp(46),dp(46)))
        val st = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),0,0,0) }
        st.addView(tv("Success!", 18f, navy, true))
        st.addView(tv("Urea booking completed successfully.", 12f, Color.rgb(36,82,59), false))
        sr.addView(st)
        success.addView(sr)
        success.addView(button("↗  OPEN FFS APP", false) { openTargetApp() }, margin(0,12,0,0))
        content.addView(success, margin(0,0,0,14))

        val workflow = roundedCard(Color.WHITE, dp(16), dp(12))
        workflow.addView(tv("AUTOMATION FLOW", 12f, green, true))
        workflow.addView(tv("Login → manual OTP → My Farm → land selection → Apply for Fertilizers → remove NPKS/DAP/MOP → Neem Coated Urea → Retailer/PACS search → Urea → Receiver → Submit", 11.5f, muted, false), margin(0,6,0,0))
        content.addView(workflow, margin(0,0,0,14))

        // Bottom branding
        val footer = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(10),dp(14),dp(10),dp(18)); setBackgroundColor(darkGreen) }
        footer.addView(tv("Powered by ", 13f, Color.WHITE, false))
        footer.addView(tv("Sushanth Chithaluri", 13f, Color.WHITE, true))
        content.addView(footer)

        root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
    }

    private fun progressRow(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val labels = arrayOf("Quantity", "Retailer", "Receiver", "Submit")
        labels.forEachIndexed { i, label ->
            val col = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER }
            val icon = if (i < 2) "✓" else (i + 1).toString()
            col.addView(circleIcon(icon, if(i<2) green else Color.rgb(224,229,233), 38), LinearLayout.LayoutParams(dp(38),dp(38)))
            col.addView(tv(label, 10.5f, if(i<2) navy else muted, i<2), margin(0,5,0,0))
            box.addView(col, LinearLayout.LayoutParams(0,-2,1f))
            if (i < 3) box.addView(tv("────", 12f, if(i<2) green else Color.LTGRAY, true), LinearLayout.LayoutParams(dp(34),-2))
        }
        return box
    }

    private fun toggleAgent() {
        if (prefs.getBoolean("agent_enabled", false)) stopAgent() else saveAndStart()
    }

    private fun stopAgent() {
        FfsAccessibilityService.requestStop(this)
        prefs.edit().putString("agent_status", "STOPPED").apply()
        updateStartButton()
        status.text = "STOPPED"
    }

    private fun updateStartButton() {
        if (!::startButton.isInitialized) return
        val running = prefs.getBoolean("agent_enabled", false)
        startButton.text = if (running) "■  STOP AGENT" else "▶  START AGENT"
        startButton.setBackgroundResource(if (running) R.drawable.stop_button else R.drawable.primary_button)
    }

    private fun saveAndStart() {
        if (!saveConfig(false)) return

        // Start the accessibility state machine first, then launch FFS automatically.
        // The user should never have to open FFS separately after pressing START AGENT.
        FfsAccessibilityService.requestStart(this)
        val pkg = packageEdit.text.toString().trim().ifEmpty { "com.agristack.fsas" }
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent == null) {
            Toast.makeText(this, "FFS app is not installed", Toast.LENGTH_LONG).show()
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        prefs.edit().putBoolean("agent_enabled", true).apply()
        updateStartButton()
        window.decorView.postDelayed({
            try { startActivity(launchIntent) }
            catch (_: Exception) {
                Toast.makeText(this, "Unable to open FFS app", Toast.LENGTH_LONG).show()
            }
        }, 250L)
    }

    private fun persistConfig() {
        val pkg = packageEdit.text.toString().trim()
        val ms = intervalEdit.text.toString().toLongOrNull()?.coerceIn(80L,30000L) ?: 100L
        val loc = retailerLocationEdit.text.toString().trim()
        val mobile = receiverMobileEdit.text.toString().trim()
        prefs.edit()
            .putString("target_package", pkg.ifEmpty { "com.agristack.fsas" })
            .putLong("poll_ms", ms)
            .putString("retailer_location", loc)
            .putString("receiver_mobile", mobile)
            .apply()
    }

    private fun saveConfig(showToast: Boolean): Boolean {
        val pkg = packageEdit.text.toString().trim()
        if (pkg.isEmpty()) { Toast.makeText(this,"Select the FFS app first",Toast.LENGTH_LONG).show(); return false }
        val mobile = receiverMobileEdit.text.toString().trim()
        if (mobile.isEmpty()) { Toast.makeText(this,"Enter the receiver mobile number",Toast.LENGTH_LONG).show(); return false }
        persistConfig()
        if (showToast) Toast.makeText(this,"Configuration saved automatically",Toast.LENGTH_SHORT).show()
        return true
    }

    private fun loadSaved() {
        intervalEdit.setText(prefs.getLong("poll_ms",100L).toString())
        retailerLocationEdit.setText(prefs.getString("retailer_location","Korlagudem"))
        receiverMobileEdit.setText(prefs.getString("receiver_mobile","") ?: "")
        packageEdit.setText(prefs.getString("target_package","com.agristack.fsas"))
        updateSelectedAppLabel()
        Toast.makeText(this,"Saved configuration loaded",Toast.LENGTH_SHORT).show()
    }

    private fun updateSelectedAppLabel() {
        val pkg=packageEdit.text.toString().trim()
        if(pkg.isEmpty()){selectedAppLabel.text="No application selected";return}
        val label=try{packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg,0)).toString()}catch(_:Exception){"Framework for Fertilizer Sale"}
        selectedAppLabel.text="$label\n($pkg)"
    }

    private fun openTargetApp() {
        val pkg = packageEdit.text.toString().trim().ifEmpty { "com.agristack.fsas" }
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) startActivity(intent) else Toast.makeText(this,"FFS app is not installed",Toast.LENGTH_LONG).show()
    }

    private fun showAppPicker() {
        val intent=Intent(Intent.ACTION_MAIN).apply{addCategory(Intent.CATEGORY_LAUNCHER)}
        val apps=packageManager.queryIntentActivities(intent,PackageManager.MATCH_ALL).map{it.activityInfo.applicationInfo}.distinctBy{it.packageName}.filter{it.packageName!=packageName}.sortedBy{packageManager.getApplicationLabel(it).toString().lowercase(Locale.ROOT)}
        if(apps.isEmpty()){Toast.makeText(this,"No launchable applications found",Toast.LENGTH_LONG).show();return}
        val labels=apps.map{"${packageManager.getApplicationLabel(it)}\n${it.packageName}"}.toTypedArray()
        AlertDialog.Builder(this).setTitle("Select FFS App").setItems(labels){_,which->packageEdit.setText(apps[which].packageName);updateSelectedAppLabel();prefs.edit().putString("target_package",apps[which].packageName).apply();Toast.makeText(this,"Selected ${packageManager.getApplicationLabel(apps[which])}",Toast.LENGTH_SHORT).show()}.setNegativeButton("Cancel",null).show()
    }

    private fun roundedCard(bg:Int,padH:Int,padV:Int)=LinearLayout(this).apply{
        orientation=LinearLayout.VERTICAL
        val gd = android.graphics.drawable.GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), Color.rgb(227,233,239))
        }
        background = gd
        setPadding(padH,padV,padH,padV)
        elevation=dp(2).toFloat()
    }
    private fun circleIcon(text:String,color:Int,size:Int)=TextView(this).apply{gravity=Gravity.CENTER;textSize=if(text.length>1)14f else 20f;setTypeface(Typeface.DEFAULT,Typeface.BOLD);setText(text);setTextColor(if(color==Color.rgb(224,229,233)) muted else Color.WHITE);setBackgroundResource(if(color==green)R.drawable.circle_green else R.drawable.circle_light)}
    private fun sectionHeader(icon:String,title:String)=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;addView(tv(icon,21f,green,true),LinearLayout.LayoutParams(dp(34),-2));addView(tv(title,18f,navy,true))}
    private fun fieldLabel(icon:String,title:String)=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;addView(tv(icon,20f,Color.rgb(37,105,235),true),LinearLayout.LayoutParams(dp(38),-2));addView(tv(title,13f,navy,true))}
    private fun helper(text:String)=tv(text,11f,Color.rgb(120,136,153),false).apply{setPadding(dp(38),0,0,0)}
    private fun edit(value:String,type:Int)=EditText(this).apply{setSingleLine(true);inputType=type;setText(value);textSize=15f;setTextColor(navy);setHintTextColor(Color.rgb(145,155,165));setBackgroundResource(R.drawable.input_bg);setPadding(dp(14),0,dp(14),0);minHeight=dp(50)}
    private fun button(text:String,primary:Boolean,onClick:()->Unit)=Button(this).apply{this.text=text;textSize=13f;isAllCaps=false;setTypeface(Typeface.DEFAULT,Typeface.BOLD);setTextColor(if(primary)Color.WHITE else navy);setBackgroundResource(if(primary)R.drawable.primary_button else R.drawable.secondary_button);setPadding(dp(8),0,dp(8),0);setOnClickListener{onClick()};minHeight=dp(40)}
    private fun actionButton(icon:String,text:String,onClick:()->Unit)=Button(this).apply{this.text="$icon\n$text";textSize=11.5f;isAllCaps=false;gravity=Gravity.CENTER;setTypeface(Typeface.DEFAULT,Typeface.BOLD);setTextColor(navy);setBackgroundResource(R.drawable.secondary_button);setOnClickListener{onClick()}}
    private fun tv(text:String,size:Float,color:Int,bold:Boolean)=TextView(this).apply{this.text=text;textSize=size;setTextColor(color);if(bold)typeface=Typeface.DEFAULT_BOLD}
    private fun margin(l:Int,t:Int,r:Int,b:Int)=LinearLayout.LayoutParams(-1,-2).apply{setMargins(dp(l),dp(t),dp(r),dp(b))}
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()
}
