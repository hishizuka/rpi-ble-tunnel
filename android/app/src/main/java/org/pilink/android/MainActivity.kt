package org.pilink.android

import android.Manifest
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private enum class Page { CONNECTION, DIAGNOSTICS, SETTINGS }
    private lateinit var store: DeviceStore
    private lateinit var toolbar: MaterialToolbar
    private lateinit var tabs: TabLayout
    private lateinit var connect: MaterialButton
    private var page = Page.CONNECTION
    private var state = LinkState()
    private var service: PiLinkService? = null
    private var bound = false
    private var discovery: PiDiscovery? = null
    private var registrationDialog: androidx.appcompat.app.AlertDialog? = null
    private var pendingRegistration = false
    private var pendingStart = false
    private var pendingDebugStart = false
    private var debugCapability: Long? = null
    private var debugProfile: DeviceProfile? = null
    private val observer: (LinkState) -> Unit = { render(it) }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(component: ComponentName, binder: IBinder) {
            service = (binder as PiLinkService.LocalBinder).service
            service!!.observe(observer)
        }
        override fun onServiceDisconnected(component: ComponentName) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DeviceStore(getPreferences(MODE_PRIVATE))
        setContentView(R.layout.activity_main)
        val splitConnectionLayout = resources.getBoolean(R.bool.split_connection_layout)
        if (!resources.getBoolean(R.bool.pin_connection_actions) && !splitConnectionLayout) {
            // Scroll the entire page in short windows so fixed actions cannot hide the device list.
            val actions = findViewById<View>(R.id.connection_actions)
            (actions.parent as LinearLayout).removeView(actions)
            val content = findViewById<LinearLayout>(R.id.connection_content)
            content.addView(actions)
            actions.setPadding(0, 0, 0, actions.paddingBottom)
        }
        if (!resources.getBoolean(R.bool.pin_connection_actions) || splitConnectionLayout) {
            val hero = findViewById<LinearLayout>(R.id.connection_hero)
            hero.minimumHeight = 0
            hero.layoutParams = LinearLayout.LayoutParams(-1, -2)
            findViewById<View>(R.id.connection_illustration)?.visibility = View.GONE
            findViewById<View>(R.id.connection_hint)?.visibility = View.GONE
        }
        if (splitConnectionLayout) {
            val actions = findViewById<View>(R.id.connection_actions)
            actions.setPadding(0, 0, 0, actions.paddingBottom)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val root = findViewById<View>(R.id.app_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        root.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            // Keep a readable content width on unfolded and landscape screens.
            val margin = ((right - left - resources.getDimensionPixelSize(R.dimen.page_max_width)) / 2).coerceAtLeast(0)
            findViewById<View>(R.id.page_frame).setPadding(margin, 0, margin, 0)
        }
        toolbar = findViewById(R.id.toolbar)
        toolbar.menu.add(0, 1, 0, R.string.settings_title).apply {
            setIcon(R.drawable.ic_settings)
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.setOnMenuItemClickListener {
            showPage(Page.SETTINGS)
            true
        }
        tabs = findViewById(R.id.tabs)
        tabs.addTab(tabs.newTab().setText(R.string.connection_tab))
        tabs.addTab(tabs.newTab().setText(R.string.diagnostics_tab))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { showPage(if (tab.position == 0) Page.CONNECTION else Page.DIAGNOSTICS) }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        connect = findViewById(R.id.connect_button)
        connect.setOnClickListener {
            if (state.running) startService(Intent(this, PiLinkService::class.java).setAction(PiLinkService.STOP))
            else requestStart()
        }
        findViewById<View>(R.id.register_empty).setOnClickListener { registerDevice() }
        findViewById<View>(R.id.add_device).setOnClickListener { registerDevice() }
        findViewById<View>(R.id.copy_command).setOnClickListener { copy("PiLink SSH", sshCommand()) }
        findViewById<View>(R.id.copy_log).setOnClickListener { copy("PiLink", state.log) }
        findViewById<View>(R.id.open_termius).setOnClickListener { openTermius() }
        findViewById<TextView>(R.id.version).text = getString(R.string.app_version, BuildConfig.VERSION_NAME)
        findViewById<View>(R.id.licenses).setOnClickListener {
            val text = assets.open("material-icons-LICENSE.txt").bufferedReader().use { it.readText() }
            MaterialAlertDialogBuilder(this).setTitle(R.string.licenses_title).setMessage(text)
                .setPositiveButton(R.string.done, null).show()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != Page.CONNECTION) showPage(Page.CONNECTION)
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
        page = runCatching { Page.valueOf(savedInstanceState?.getString("page") ?: Page.CONNECTION.name) }.getOrDefault(Page.CONNECTION)
        render(state)
        showPage(page)
        handleDebugIntent(intent)
    }

    private fun showPage(next: Page) {
        page = next
        findViewById<View>(R.id.connection_page).visibility = if (next == Page.CONNECTION) View.VISIBLE else View.GONE
        findViewById<View>(R.id.diagnostics_page).visibility = if (next == Page.DIAGNOSTICS) View.VISIBLE else View.GONE
        findViewById<View>(R.id.settings_page).visibility = if (next == Page.SETTINGS) View.VISIBLE else View.GONE
        tabs.visibility = if (next == Page.SETTINGS) View.GONE else View.VISIBLE
        toolbar.setTitle(if (next == Page.SETTINGS) R.string.settings_title else R.string.app_name)
        if (next == Page.SETTINGS) {
            toolbar.logo = null
            toolbar.setNavigationIcon(R.drawable.ic_back)
            toolbar.navigationContentDescription = getString(R.string.back)
            toolbar.setNavigationOnClickListener { showPage(Page.CONNECTION) }
        } else {
            toolbar.navigationIcon = null
            toolbar.logo = AppCompatResources.getDrawable(this, R.drawable.ic_link)?.mutate()?.apply { setTint(color(R.color.pilink_primary)) }
            toolbar.setNavigationOnClickListener(null)
        }
        toolbar.menu.findItem(1).isVisible = next != Page.SETTINGS
        if (next != Page.SETTINGS) {
            val position = if (next == Page.CONNECTION) 0 else 1
            if (tabs.selectedTabPosition != position) tabs.selectTab(tabs.getTabAt(position))
        }
    }

    private fun render(current: LinkState) {
        state = current
        if (state.running) store.devices.firstOrNull { it.hostname == state.targetName }?.let {
            if (store.selectedId != it.id) store.select(it.id)
        }
        renderDevices()
        val status = when {
            state.ready -> R.string.connected
            state.retrying -> R.string.reconnecting_wait
            state.running -> R.string.connecting
            state.status.startsWith("接続エラー") -> R.string.connection_error
            else -> R.string.disconnected
        }
        findViewById<TextView>(R.id.connection_status).setText(status)
        val target = if (state.running) store.devices.firstOrNull { it.hostname == state.targetName }?.hostname
            ?: state.targetName else store.selected?.hostname
        findViewById<TextView>(R.id.selected_device).text = target?.let { getString(R.string.selected_device, it) }
            ?: getString(R.string.empty_devices)
        connect.setText(if (state.running) {
            if (state.ready) R.string.disconnect else if (state.retrying) R.string.cancel_reconnect else R.string.cancel_connection
        } else R.string.connect)
        connect.isEnabled = state.running || store.selected != null
        findViewById<TextView>(R.id.mode_value).setText(when (state.mode) {
            ConnectionMode.INTERNET -> R.string.mode_internet
            ConnectionMode.MULTIPLEX -> R.string.mode_mux
            ConnectionMode.SSH -> R.string.mode_ssh
            null -> R.string.mode_unknown
        })
        findViewById<TextView>(R.id.mode_detail).setText(when (state.mode) {
            ConnectionMode.INTERNET -> R.string.mode_internet_help
            ConnectionMode.MULTIPLEX -> R.string.mode_mux_help
            ConnectionMode.SSH -> R.string.mode_ssh_help
            null -> R.string.mode_unknown_help
        })
        findViewById<TextView>(R.id.diagnostic_status).text = state.status
        findViewById<TextView>(R.id.diagnostic_log).text = state.log.ifEmpty { getString(R.string.no_log) }
        findViewById<View>(R.id.copy_command).isEnabled = sshCommand().isNotEmpty()
        findViewById<View>(R.id.copy_log).isEnabled = state.log.isNotEmpty()
        findViewById<View>(R.id.open_termius).isEnabled = state.ready
        findViewById<TextView>(R.id.ssh_endpoint).text = getString(R.string.ssh_endpoint,
            store.selected?.hostname.orEmpty(), if (state.running) state.port else store.selected?.port ?: 2222)
    }

    private fun renderDevices() {
        val list = findViewById<LinearLayout>(R.id.device_list)
        list.removeAllViews()
        store.devices.forEach { profile ->
            val selected = profile.id == store.selectedId
            val tint = color(if (selected) R.color.pilink_primary else R.color.pilink_muted)
            val card = MaterialCardView(this).apply {
                radius = dp(16).toFloat(); cardElevation = 0f
                strokeWidth = dp(1); setStrokeColor(color(R.color.pilink_outline))
                setCardBackgroundColor(color(if (selected) R.color.pilink_selected else R.color.pilink_surface))
                setCardForegroundColor(ColorStateList.valueOf(android.graphics.Color.TRANSPARENT))
                // Configure the icon before a checked-state animation can start.
                isCheckable = true; checkedIcon = null; isChecked = selected
                isFocusable = true; isSelected = selected
                contentDescription = profile.hostname + if (selected) ", ${getString(R.string.selected)}" else ""
                setOnClickListener { if (!state.running) { store.select(profile.id); render(state) } }
                isClickable = !state.running
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(76); setPadding(dp(16), dp(12), dp(16), dp(12))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            row.addView(ImageView(this).apply { setImageResource(R.drawable.ic_memory); imageTintList = ColorStateList.valueOf(tint) },
                LinearLayout.LayoutParams(dp(44), dp(44)))
            row.addView(View(this).apply { setBackgroundColor(color(R.color.pilink_outline)) },
                LinearLayout.LayoutParams(dp(1), dp(32)).apply { marginStart = dp(16); marginEnd = dp(16) })
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(TextView(this).apply {
                text = profile.hostname; textSize = 20f; setTextColor(color(R.color.pilink_on_surface))
                setTypeface(null, Typeface.BOLD); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(ImageView(this).apply {
                setImageResource(if (selected) R.drawable.ic_checked else R.drawable.ic_unchecked)
                imageTintList = ColorStateList.valueOf(tint)
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(12) })
            card.addView(row)
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        findViewById<View>(R.id.register_empty).visibility = if (store.devices.isEmpty()) View.VISIBLE else View.GONE
        val settings = findViewById<LinearLayout>(R.id.settings_devices)
        settings.removeAllViews()
        store.devices.forEach { profile ->
            val button = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.edit_named_device, profile.hostname)
                isEnabled = !state.running
                setOnClickListener { editDevice(profile) }
            }
            settings.addView(button, LinearLayout.LayoutParams(-1, -2))
        }
        findViewById<View>(R.id.add_device).isEnabled = !state.running && store.devices.size < DeviceStore.LIMIT
    }

    private fun registerDevice() {
        if (state.running) { toast(R.string.editing_while_connected); return }
        if (store.devices.size >= DeviceStore.LIMIT) { toast(R.string.device_limit); return }
        val required = PiLinkService.bluetoothPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (required.isNotEmpty()) { pendingRegistration = true; requestPermissions(required.toTypedArray(), 3); return }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        val status = TextView(this).apply { setText(R.string.discovering_pi); setTextColor(color(R.color.pilink_muted)) }
        list.addView(status)
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.add_device)
            .setView(ScrollView(this).apply { addView(list) })
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.enter_hostname) { _, _ -> editDevice(null) }.create()
        registrationDialog?.dismiss()
        registrationDialog = dialog
        dialog.setOnDismissListener { discovery?.stop(); discovery = null; registrationDialog = null }
        dialog.show()
        val foundHosts = mutableSetOf<String>()
        discovery = PiDiscovery(this, { name, address ->
            if (foundHosts.add(name)) {
                status.setText(R.string.choose_pi)
                list.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = name; isEnabled = store.devices.none { it.hostname == name }
                    setOnClickListener {
                        if (state.running) { dialog.dismiss(); return@setOnClickListener }
                        val profile = DeviceProfile(UUID.randomUUID().toString(), name, address = address)
                        store.save(profile); dialog.dismiss(); render(state)
                    }
                }, LinearLayout.LayoutParams(-1, -2))
            }
        }, { message -> if (foundHosts.isEmpty()) status.text = message })
        discovery!!.start()
    }

    private fun openTermius() {
        if (!state.ready) { toast(R.string.connect_before_termius); return }
        val launch = packageManager.getLaunchIntentForPackage("com.server.auditor.ssh.client")
        if (launch == null) { toast(R.string.termius_missing); return }
        try { startActivity(launch) } catch (_: ActivityNotFoundException) { toast(R.string.termius_missing) }
    }

    private fun editDevice(existing: DeviceProfile?) {
        if (state.running) { toast(R.string.editing_while_connected); return }
        if (existing == null && store.devices.size >= DeviceStore.LIMIT) { toast(R.string.device_limit); return }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), 0)
        }
        fun field(label: Int, initial: String): TextInputEditText {
            val box = TextInputLayout(this).apply {
                hint = getString(label); boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            }
            val input = TextInputEditText(box.context).apply {
                setSingleLine(); setText(initial)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            box.addView(input, LinearLayout.LayoutParams(-1, -2))
            form.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            return input
        }
        val hostname = field(R.string.hostname, existing?.hostname.orEmpty())
        form.addView(TextView(this).apply { text = getString(R.string.hostname_help); setTextColor(color(R.color.pilink_muted)) })
        val scroll = ScrollView(this).apply { addView(form) }
        val builder = MaterialAlertDialogBuilder(this).setTitle(if (existing == null) R.string.add_device else R.string.edit_device)
            .setView(scroll).setPositiveButton(R.string.save, null).setNegativeButton(R.string.cancel, null)
        if (existing != null) builder.setNeutralButton(R.string.delete) { _, _ ->
            MaterialAlertDialogBuilder(this).setTitle(R.string.delete_device_title)
                .setMessage(getString(R.string.delete_device_message, existing.hostname))
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.delete) { _, _ ->
                    store.remove(existing.id); render(state)
                }.show()
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (state.running) { dialog.dismiss(); toast(R.string.editing_while_connected); return@setOnClickListener }
                val name = DeviceProfile.normalizeHostname(hostname.text.toString())
                val sameHost = existing?.hostname == name
                val profile = DeviceProfile(existing?.id ?: UUID.randomUUID().toString(), name, existing?.port ?: 2222,
                    address = if (sameHost) existing?.address else null,
                    legacyBluetoothName = if (sameHost) existing?.legacyBluetoothName else null)
                if (runCatching { profile.validated() }.isFailure) { toast(R.string.device_validation_error); return@setOnClickListener }
                if (store.devices.any { it.id != profile.id && it.hostname == profile.hostname }) {
                    toast(R.string.duplicate_device); return@setOnClickListener
                }
                store.save(profile); dialog.dismiss(); render(state)
            }
        }
        dialog.show()
    }

    private fun sshCommand(): String {
        val profile = store.selected ?: return ""
        if (!state.running) return profile.sshCommand()
        return profile.sshCommand(state.port)
    }
    private fun requestStart() {
        val profile = debugProfile ?: store.selected ?: return
        val required = PiLinkService.bluetoothPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (required.isNotEmpty()) { pendingStart = true; requestPermissions(required.toTypedArray(), 1); return }
        val capability = debugCapability ?: PiLinkProfile.AUTO
        debugCapability = null; debugProfile = null
        startForegroundService(Intent(this, PiLinkService::class.java).setAction(PiLinkService.START)
            .putExtra("name", profile.hostname).putExtra("port", profile.port)
            .putExtra("host_key_alias", "${profile.hostname}.local").putExtra("capability", capability)
            .putExtra("address", profile.address).putExtra("legacy_name", profile.legacyBluetoothName))
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 3 && pendingRegistration) {
            pendingRegistration = false
            if (PiLinkService.bluetoothPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) registerDevice()
            else toast(R.string.bluetooth_permission)
        }
        if (requestCode == 1 && pendingStart) {
            pendingStart = false
            if (PiLinkService.bluetoothPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) requestStart()
            else toast(R.string.bluetooth_permission)
        }
    }
    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, PiLinkService::class.java), connection, Context.BIND_AUTO_CREATE)
    }
    override fun onStop() {
        registrationDialog?.dismiss(); registrationDialog = null
        discovery?.stop(); discovery = null
        service?.removeObserver(observer); service = null
        if (bound) { unbindService(connection); bound = false }
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page.name); super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); handleDebugIntent(intent)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) startPendingDebugConnection()
    }
    private fun startPendingDebugConnection() {
        if (!pendingDebugStart || !hasWindowFocus() || getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        pendingDebugStart = false; requestStart()
    }
    private fun handleDebugIntent(intent: Intent) {
        // Validation commands remain isolated from ordinary UI preferences.
        if (!BuildConfig.DEBUG) return
        if (intent.hasExtra("echo_port")) {
            val port = intent.getIntExtra("echo_port", 0); intent.removeExtra("echo_port")
            if (port == 0) DebugEchoServer.stop() else DebugEchoServer.start(port)
        }
        if (intent.getBooleanExtra("disconnect", false)) {
            intent.removeExtra("disconnect")
            startService(Intent(this, PiLinkService::class.java).setAction(PiLinkService.STOP))
        }
        if (intent.getBooleanExtra("connect", false)) {
            intent.removeExtra("connect")
            val requested = intent.getStringExtra("name") ?: store.selected?.hostname ?: return
            val matched = store.devices.firstOrNull {
                it.hostname == DeviceProfile.normalizeHostname(requested) || it.legacyBluetoothName == requested
            }
            val base = matched ?: DeviceProfile("debug", DeviceProfile.normalizeHostname(requested))
            debugProfile = base.copy(port = intent.getIntExtra("port", base.port)).validated()
            debugCapability = when {
                intent.getBooleanExtra("internet", false) -> PiLinkProfile.INTERNET
                intent.getBooleanExtra("multiplex", false) -> PiLinkProfile.MUX
                intent.hasExtra("internet") || intent.hasExtra("multiplex") -> PiLinkProfile.SSH
                else -> null
            }
            showPage(Page.CONNECTION); pendingDebugStart = true
            window.decorView.post { startPendingDebugConnection() }
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = getColor(id)
    private fun toast(message: Int) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun copy(label: String, text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        toast(R.string.copied)
    }
}
