package net.joshe.pandplay

import android.annotation.SuppressLint
import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.menu.MenuBuilder
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.onNavDestinationSelected
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import net.joshe.pandplay.databinding.ActivityMainBinding
import net.joshe.pandplay.db.Station
import net.joshe.pandplay.ui.LibraryViewModel

class MainActivity : AppCompatActivity() {
    private val libraryViewModel: LibraryViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private val stationMenuIds: MutableMap<Int, Station> = mutableMapOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        navController = findNavController(R.id.nav_host_fragment_activity_main)

        libraryViewModel.createBrowser(this, ComponentName(this, PlayerService::class.java))

        val appBarConfiguration = AppBarConfiguration(
            setOf(R.id.navigation_player, R.id.navigation_playlist))
        setupActionBarWithNavController(navController, appBarConfiguration)
        binding.navView.setupWithNavController(navController)

        libraryViewModel.stationsChanged.observe(this) { invalidateOptionsMenu() }

        if (!libraryViewModel.haveValidCredentials())
            navController.navigate(R.id.navigation_prefs_login)

        libraryViewModel.playlistTitle.observe(this) { title ->
            supportActionBar?.subtitle = title?:""
        }
    }

    override fun onStart() {
        Log.v("MAIN", "onStart")
        super.onStart()
        libraryViewModel.startBrowser()
    }

    override fun onStop() {
        Log.v("MAIN", "onStop")
        super.onStop()
        libraryViewModel.stopBrowser()
    }

    @SuppressLint("RestrictedApi")
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.top_appbar_menu, menu)
        if (menu is MenuBuilder)
            menu.setOptionalIconsVisible(true)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val ret = super.onPrepareOptionsMenu(menu)
        val submenu = menu.findItem(R.id.submenu_stations).subMenu
        val stations = libraryViewModel.getStationList()
        val selected = libraryViewModel.getCurrentStation()
        val ids: MutableList<Int> = stationMenuIds.keys.toMutableList()
        while (ids.size < stations.size)
            ids.add(View.generateViewId())

        Log.e("MAIN", "building options menu with ${stations.size} stations, ${ids.size} cached ids and selecting ${selected?.stationName}")
        stationMenuIds.clear()
        submenu.clear()
        stations.forEachIndexed { idx, station ->
            val item = submenu.add(1, ids[idx], idx, station.stationName)
            stationMenuIds[item.itemId] = station
            if (station == selected)
                item.isChecked = true
        }
        submenu.setGroupCheckable(1, true, true)
        return ret
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        navController = findNavController(R.id.nav_host_fragment_activity_main)
        if (item.onNavDestinationSelected(navController))
            return true
        when (item.itemId) {
            android.R.id.home -> navController.navigateUp()
            R.id.menuaction_syncStations -> libraryViewModel.reloadStations()
            R.id.menuaction_downloadSongs -> libraryViewModel.downloadSongs(this)
            in stationMenuIds -> libraryViewModel.changeStation(stationMenuIds.getValue(item.itemId).stationId)
            else -> {
                Log.v("MAIN", "unknown menuitem ${item.itemId} - ${item.title} -> ${stationMenuIds[item.itemId]?.stationName}")
                return super.onOptionsItemSelected(item)
            }
        }
        return true
    }
}
