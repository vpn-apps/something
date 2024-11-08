package io.github.saeeddev94.xray.activity

import XrayCore.XrayCore
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.annotation.RequiresApi
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import io.github.saeeddev94.xray.BuildConfig
import io.github.saeeddev94.xray.R
import io.github.saeeddev94.xray.Settings
import io.github.saeeddev94.xray.adapter.ProfileAdapter
import io.github.saeeddev94.xray.database.XrayDatabase
import io.github.saeeddev94.xray.databinding.ActivityMainBinding
import io.github.saeeddev94.xray.dto.ProfileList
import io.github.saeeddev94.xray.helper.HttpHelper
import io.github.saeeddev94.xray.helper.LinkHelper
import io.github.saeeddev94.xray.helper.ProfileTouchHelper
import io.github.saeeddev94.xray.service.TProxyService
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.net.URISyntaxException

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var vpnService: TProxyService
    private var vpnLauncher = registerForActivityResult(StartActivityForResult()) {
        toggleVpnService()
    }

    private lateinit var profilesList: RecyclerView
    private lateinit var profileAdapter: ProfileAdapter
    private lateinit var profiles: ArrayList<ProfileList>
    private var profileLauncher = registerForActivityResult(StartActivityForResult()) {
        if (it.resultCode != RESULT_OK || it.data == null) return@registerForActivityResult
        val index = it.data!!.getIntExtra("index", -1)
        val id = it.data!!.getLongExtra("id", 0L)
        onProfileActivityResult(id, index)
    }

    private var vpnServiceBound: Boolean = false
    private var serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as TProxyService.ServiceBinder
            vpnService = binder.getService()
            vpnServiceBound = true
            setVpnServiceStatus()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            vpnServiceBound = false
        }
    }

    private val toggleVpnAction: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                TProxyService.START_VPN_SERVICE_ACTION_NAME -> vpnStartStatus()
                TProxyService.STOP_VPN_SERVICE_ACTION_NAME -> vpnStopStatus()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        Settings.sync(applicationContext)
        binding.toggleButton.setOnClickListener { onToggleButtonClick() }
        binding.pingBox.setOnClickListener { ping() }
        binding.navView.menu.findItem(R.id.appVersion).title = BuildConfig.VERSION_NAME
        binding.navView.menu.findItem(R.id.xrayVersion).title = XrayCore.version()
        binding.navView.setNavigationItemSelectedListener(this)
        ActionBarDrawerToggle(this, binding.drawerLayout, binding.toolbar, R.string.drawerOpen, R.string.drawerClose).also {
            binding.drawerLayout.addDrawerListener(it)
            it.syncState()
        }
        getProfiles()
        val deepLink: Uri? = intent?.data
        deepLink?.let {
            val pathSegments = it.pathSegments
            if (pathSegments.size > 0) processLink(pathSegments[0])
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onStart() {
        super.onStart()
        Intent(this, TProxyService::class.java).also {
            bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        IntentFilter().also {
            it.addAction(TProxyService.START_VPN_SERVICE_ACTION_NAME)
            it.addAction(TProxyService.STOP_VPN_SERVICE_ACTION_NAME)
            registerReceiver(toggleVpnAction, it, RECEIVER_NOT_EXPORTED)
        }
    }

    override fun onStop() {
        super.onStop()
        unbindService(serviceConnection)
        unregisterReceiver(toggleVpnAction)
        vpnServiceBound = false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 1) onToggleButtonClick()
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.newProfile -> {
                profileLauncher.launch(profileIntent())
            }
            R.id.fromClipboard -> {
                val clipboardManager: ClipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipData: ClipData? = clipboardManager.primaryClip
                val clipText: String = if (clipData != null && clipData.itemCount > 0) clipData.getItemAt(0).text.toString().trim() else ""
                processLink(clipText)
            }
        }
        return true
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.assets -> Intent(applicationContext, AssetsActivity::class.java)
            R.id.logs -> Intent(applicationContext, LogsActivity::class.java)
            R.id.excludedApps -> Intent(applicationContext, ExcludeActivity::class.java)
            R.id.settings -> Intent(applicationContext, SettingsActivity::class.java)
            else -> null
        }.also {
            if (it != null) startActivity(it)
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun setVpnServiceStatus() {
        if (!vpnServiceBound) return
        if (vpnService.getIsRunning()) {
            vpnStartStatus()
        } else {
            vpnStopStatus()
        }
    }

    private fun vpnStartStatus() {
        binding.toggleButton.text = getString(R.string.vpnStop)
        binding.toggleButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primaryColor))
        binding.pingResult.text = getString(R.string.pingConnected)
    }

    private fun vpnStopStatus() {
        binding.toggleButton.text = getString(R.string.vpnStart)
        binding.toggleButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.btnColor))
        binding.pingResult.text = getString(R.string.pingNotConnected)
    }

    private fun onToggleButtonClick() {
        if (!hasPostNotification()) return
        VpnService.prepare(this).also {
            if (it == null) {
                toggleVpnService()
                return
            }
            vpnLauncher.launch(it)
        }
    }

    private fun toggleVpnService() {
        if (vpnService.getIsRunning()) {
            Intent(TProxyService.STOP_VPN_SERVICE_ACTION_NAME).also {
                it.`package` = BuildConfig.APPLICATION_ID
                sendBroadcast(it)
            }
            return
        }
        Intent(applicationContext, TProxyService::class.java).also {
            startForegroundService(it)
        }
    }

    private fun profileSelect(index: Int, profile: ProfileList) {
        if (vpnService.getIsRunning()) return
        val selectedProfile = Settings.selectedProfile
        Thread {
            val ref = if (selectedProfile > 0L) XrayDatabase.ref(applicationContext).profileDao().find(selectedProfile) else null
            runOnUiThread {
                Settings.selectedProfile = if (selectedProfile == profile.id) 0L else profile.id
                Settings.save(applicationContext)
                profileAdapter.notifyItemChanged(index)
                if (ref != null && ref.index != index) profileAdapter.notifyItemChanged(ref.index)
            }
        }.start()
    }

    private fun profileEdit(index: Int, profile: ProfileList) {
        if (vpnService.getIsRunning() && Settings.selectedProfile == profile.id) return
        profileLauncher.launch(profileIntent(index, profile.id))
    }

    private fun profileDelete(index: Int, profile: ProfileList) {
        if (vpnService.getIsRunning() && Settings.selectedProfile == profile.id) return
        val selectedProfile = Settings.selectedProfile
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Profile#${profile.index + 1} ?")
            .setMessage("\"${profile.name}\" will delete forever !!")
            .setNegativeButton("No", null)
            .setPositiveButton("Yes") { dialog, _ ->
                dialog?.dismiss()
                Thread {
                    val db = XrayDatabase.ref(applicationContext)
                    val ref = db.profileDao().find(profile.id)
                    val id = ref.id
                    db.profileDao().delete(ref)
                    db.profileDao().fixDeleteIndex(index)
                    runOnUiThread {
                        if (selectedProfile == id) {
                            Settings.selectedProfile = 0L
                            Settings.save(applicationContext)
                        }
                        profiles.removeAt(index)
                        profileAdapter.notifyItemRemoved(index)
                        profileAdapter.notifyItemRangeChanged(index, profiles.size - index)
                    }
                }.start()
            }.show()
    }

    private fun profileIntent(index: Int = -1, id: Long = 0L, name: String = "", config: String = ""): Intent {
        return Intent(applicationContext, ProfileActivity::class.java).also {
            it.putExtra("index", index)
            it.putExtra("id", id)
            if (name.isNotEmpty()) it.putExtra("name", name)
            if (config.isNotEmpty()) it.putExtra("config", config.replace("\\/", "/"))
        }
    }

    private fun onProfileActivityResult(id: Long, index: Int) {
        if (index == -1) {
            Thread {
                val newProfile = XrayDatabase.ref(applicationContext).profileDao().find(id)
                runOnUiThread {
                    profiles.add(0, ProfileList.fromProfile(newProfile))
                    profileAdapter.notifyItemRangeChanged(0, profiles.size)
                }
            }.start()
            return
        }
        Thread {
            val profile = XrayDatabase.ref(applicationContext).profileDao().find(id)
            runOnUiThread {
                profiles[index] = ProfileList.fromProfile(profile)
                profileAdapter.notifyItemChanged(index)
            }
        }.start()
    }

    private fun getProfiles() {
        Thread {
            val list = XrayDatabase.ref(applicationContext).profileDao().all()
            runOnUiThread {
                profiles = ArrayList(list)
                profilesList = binding.profilesList
                profileAdapter = ProfileAdapter(applicationContext, profiles, object : ProfileAdapter.ProfileClickListener {
                    override fun profileSelect(index: Int, profile: ProfileList) = this@MainActivity.profileSelect(index, profile)
                    override fun profileEdit(index: Int, profile: ProfileList) = this@MainActivity.profileEdit(index, profile)
                    override fun profileDelete(index: Int, profile: ProfileList) = this@MainActivity.profileDelete(index, profile)
                })
                profilesList.adapter = profileAdapter
                profilesList.layoutManager = LinearLayoutManager(applicationContext)
                ItemTouchHelper(ProfileTouchHelper(profileAdapter)).also { it.attachToRecyclerView(profilesList) }
            }
        }.start()
    }

    private fun processLink(link: String) {
        val uri = try {
            URI(link)
        } catch (error: URISyntaxException) {
            null
        }
        if (uri == null) {
            Toast.makeText(applicationContext, "Invalid Uri", Toast.LENGTH_SHORT).show()
            return
        }
        if (uri.scheme == "http" || uri.scheme == "https") {
            getConfig(uri)
            return
        }
        val linkHelper = LinkHelper(link)
        if (!linkHelper.isValid()) {
            Toast.makeText(applicationContext, "Invalid Link", Toast.LENGTH_SHORT).show()
            return
        }
        val name = LinkHelper.remark(uri)
        val json = linkHelper.json()
        profileLauncher.launch(profileIntent(name = name, config = json))
    }

    private fun getConfig(uri: URI) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.loading_dialog, LinearLayout(this))
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.show()
        Thread {
            try {
                val config = HttpHelper().get(uri.toString())
                runOnUiThread {
                    dialog.dismiss()
                    try {
                        val name = LinkHelper.remark(uri)
                        val json = JSONObject(config).toString(2)
                        profileLauncher.launch(profileIntent(name = name, config = json))
                    } catch (error: JSONException) {
                        Toast.makeText(applicationContext, error.message, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    dialog.dismiss()
                    Toast.makeText(applicationContext, error.message, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun ping() {
        if (!vpnService.getIsRunning()) return
        binding.pingResult.text = getString(R.string.pingTesting)
        Thread {
            val delay = HttpHelper().measureDelay()
            runOnUiThread {
                binding.pingResult.text = delay
            }
        }.start()
    }

    private fun hasPostNotification(): Boolean {
        val sharedPref = getSharedPreferences("app", Context.MODE_PRIVATE)
        val key = "request_notification_permission"
        val askedBefore = sharedPref.getBoolean(key, false)
        if (askedBefore) return true
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            sharedPref.edit().putBoolean(key, true).apply()
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            return false
        }
        return true
    }
}
