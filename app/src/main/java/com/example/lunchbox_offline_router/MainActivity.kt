package com.example.lunchbox_offline_router

import android.annotation.SuppressLint
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mapbox.api.directions.v5.models.DirectionsResponse
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.geojson.BoundingBox
import com.mapbox.geojson.Point
import org.maplibre.navigation.android.navigation.ui.v5.route.NavigationMapRoute


import org.json.JSONObject
import org.maplibre.android.location.permissions.PermissionsListener
import org.maplibre.android.location.permissions.PermissionsManager
import org.maplibre.android.maps.MapLibreMap
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File

class MainActivity : ActivityCompat(), PermissionsListener {

    private val ACCESS_TOKEN =
        "YOUR_ACCESS_TOKEN" // May not be needed with MapLibre depending on your tile provider
    private val maplibre_styleURL =
        "https://api.maptiler.com/maps/basic/style.json?key=g77hl5yLKRrRBG5V6YlR"

    private lateinit var maplibreMap: MapLibreMap

    private var permissionsManager: PermissionsManager =
        PermissionsManager(this)

    private var own_location: Point? = null
    private var destination: Point? = null

    private var navigationMapRoute: NavigationMapRoute? = null
    private var route: DirectionsRoute? = null

    // JSON encoding/decoding for offline region name
    val JSON_CHARSET: String = "UTF-8"
    val JSON_FIELD_REGION_NAME = "FIELD_REGION_NAME"

    // Offline Manager for offline Maps
    private lateinit var offlineManager: org.maplibre.android.offline.OfflineManager

    // Navigation
    private lateinit var navigation: Libre
    private var running: Boolean = false
    private var tracking: Boolean = false

    // Offline Navigation
    private lateinit var offlineRouter: MapboxOfflineRouter
    private var configured_offline_route: Int = 0
    private lateinit var versioncode: String
    private lateinit var f: File

    // bounding box for offline download
    private var boundingBox: BoundingBox = BoundingBox.fromPoints(
        org.maplibre.android.geometry.Point.fromLngLat(-7.834557, 61.3895),
        org.maplibre.android.geometry.Point.fromLngLat(-6.0010, 62.4348)
    )

    // progress bar offline download
    private var isEndNotified: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.NavigationViewLight) //required for instructionView!!
        super.onCreate(savedInstanceState)
        org.maplibre.android.MapLibre.getInstance(applicationContext)
        setContentView(R.layout.activity_main)

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)

        //Navigation
        var nav_options: MapboxNavigationOptions =
            MapboxNavigationOptions.builder().isDebugLoggingEnabled(true).build()
        navigation = MapboxNavigation(applicationContext, ACCESS_TOKEN, nav_options)
        navigation.addNavigationEventListener(this)
        navigation.addMilestoneEventListener(this)
    }

    override fun onMapReady(maplibreMap: org.maplibre.android.maps.MaplibreMap) {
        this.maplibreMap = maplibreMap
        maplibreMap.setStyle(
            org.maplibre.android.maps.Style.Builder().fromUri(
                maplibre_styleURL
            )
        ) {
            // Enable LocationComponent
            enableLocationComponent(it)

            //add OnClickListener
            maplibreMap.addOnMapClickListener(this@MainActivity)

            //displaying a route on Mapview
            navigationMapRoute = NavigationMapRoute(null, mapView, maplibreMap)

            // ProgressBar to display Download progress
            progress_bar.visibility = View.VISIBLE
        }
    }

    override fun onMapClick(point: org.maplibre.android.geometry.LatLng): Boolean {
        //get clicked coordinates
        destination =
            org.maplibre.android.geometry.Point.fromLngLat(point.longitude, point.latitude)

        // make route request
        build_nav_route()

        return true
    }

    private fun build_nav_route() {
        NavigationRoute.builder(this)
            .accessToken(ACCESS_TOKEN)
            .origin(own_location!!)
            .destination(destination!!)
            .build()
            .getRoute(object : Callback<DirectionsResponse> {
                override fun onResponse(
                    call: Call<DirectionsResponse>,
                    response: Response<DirectionsResponse>
                ) {
                    if (response.isSuccessful && response.body() != null) {
                        try {
                            route = response.body()!!
                                .routes()[0] // Getting directionsRoute object for navigation

                            // displaying a route on MapView
                            var routes = response.body()!!.routes()
                            navigationMapRoute!!.addRoutes(routes)

                            //Start Navigation given a directionsRoute object
                            tracking = true // track device position
                            navigation.startNavigation(route!!)

                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Invalid Route", Toast.LENGTH_LONG)
                                .show()
                        }
                    } else {
                        Log.e("Error", "Route Request: " + response.errorBody().toString())
                    }
                }

                override fun onFailure(call: Call<DirectionsResponse>, t: Throwable) {
                    Log.e("Failure", "Route Request error")

                    if (configured_offline_route == 1) {
                        try {
                            createOfflineRoute()
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Offline router exception: " + e.message)
                        }
                    } else {
                        Toast.makeText(
                            applicationContext,
                            "Offline Router not configured.",
                            Toast.LENGTH_LONG
                        ).show()
                        // Try to configure the offline Router
                        try {
                            configureOfflineRouter() // configure offline router. Offline Routing tiles should already be downloaded.
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error configuring offline router: " + e.message)
                        }
                    }
                }
            })
    }

    @SuppressLint("MissingPermission")
    private fun enableLocationComponent(loadedMapStyle: org.maplibre.android.maps.Style) {
        // Check if permissions are enabled and if not request
        if (org.maplibre.android.permissions.PermissionsManager.areLocationPermissionsGranted(this)) {
            // Create and customize the LocationComponent's options
            val customLocationComponentOptions =
                org.maplibre.android.location.LocationComponentOptions.builder(this)
                    .trackingGesturesManagement(true)
                    .accuracyColor(
                        ContextCompat.getColor(
                            this,
                            R.color.mapboxGreen
                        )
                    )
                    .build()

            val locationComponentActivationOptions =
                org.maplibre.android.location.LocationComponentActivationOptions.builder(
                    this,
                    loadedMapStyle
                )
                    .locationComponentOptions(customLocationComponentOptions)
                    .build()

            // Get an instance of the LocationComponent and then adjust its settings
            maplibreMap.locationComponent.apply {
                // Activate the LocationComponent with options
                activateLocationComponent(locationComponentActivationOptions)

                // Enable to make the LocationComponent visible
                isLocationComponentEnabled = true

                // Set the LocationComponent's camera mode
                cameraMode = org.maplibre.android.location.modes.CameraMode.TRACKING

                // Set the LocationComponent's render mode
                renderMode = org.maplibre.android.location.modes.RenderMode.COMPASS

                own_location = org.maplibre.android.geometry.Point.fromLngLat(
                    maplibreMap.locationComponent.lastKnownLocation!!.longitude,
                    maplibreMap.locationComponent.lastKnownLocation!!.latitude
                )

                // Kick off map tiles download
                init_offline_maps(loadedMapStyle.uri)
            }
        } else {
            permissionsManager = org.maplibre.android.permissions.PermissionsManager(this)
            permissionsManager.requestLocationPermissions(this)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        permissionsManager.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onExplanationNeeded(permissionsToExplain: List<String>) {
        Toast.makeText(this, "Explanation", Toast.LENGTH_LONG).show()
    }

    override fun onPermissionResult(granted: Boolean) {
        if (granted) {
            enableLocationComponent(maplibreMap.style!!)
        } else {
            Toast.makeText(this, "User did not grant permission!", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // Offline Maps -----------------------------------------
    private fun init_offline_maps(styleURL: String) {
        // Set up the OfflineManager
        offlineManager = org.maplibre.android.offline.OfflineManager.getInstance(this@MainActivity)

        // Create a bounding box for the offline region
        val bounds: org.maplibre.android.geometry.LatLngBounds =
            maplibreMap.projection.visibleRegion.latLngBounds
        val minZoom: Double = maplibreMap.cameraPosition.zoom
        val maxZoom: Double = maplibreMap.maxZoomLevel

        // Define the offline region
        val definition = org.maplibre.android.offline.OfflineTilePyramidRegionDefinition(
            styleURL,
            bounds,
            minZoom,
            maxZoom,
            this@MainActivity.resources.displayMetrics.density
        )

        // Define the name of the downloaded region
        var metadata: ByteArray?
        try {
            val jsonObject = JSONObject()
            jsonObject.put(JSON_FIELD_REGION_NAME, "Download region 1")
            val json = jsonObject.toString()
            metadata = json.toByteArray(charset(JSON_CHARSET))
        } catch (exception: Exception) {
            Log.e("MainActivity", "Failed to encode metadata: " + exception.message)
            metadata = null
        }

        if (definition != null && metadata != null) {
            download_offline_map_tiles(definition, metadata)
        }
    }

    private fun download_offline_map_tiles(
        definition: org.maplibre.android.offline.OfflineTilePyramidRegionDefinition,
        metadata: ByteArray
    ) {
        // Create the region asynchronously
        offlineManager.createOfflineRegion(definition, metadata,
            object : org.maplibre.android.offline.OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: org.maplibre.android.offline.OfflineRegion) {
                    offlineRegion.setDownloadState(org.maplibre.android.offline.OfflineRegion.STATE_ACTIVE)

                    // Monitor the download progress using setObserver
                    offlineRegion.setObserver(object :
                        org.maplibre.android.offline.OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: org.maplibre.android.offline.OfflineRegionStatus) {
                            // Calculate the download percentage
                            val percentage = if (status.requiredResourceCount >= 0)
                                100.0 * status.completedResourceCount / status.requiredResourceCount else 0.0

                            // update ProgressBar with current percentage
                            progress_bar.progress = percentage.toInt()

                            if (status.isComplete) {
                                // Download complete
                                Log.d("MainActivity", "Region downloaded successfully.")
                                // ProgressBar to display Download progress
                                progress_bar.visibility = View.INVISIBLE

                                // Kick off download of offline routing tiles
                                init_offline_routing_tiles()
                            } else if (status.isRequiredResourceCountPrecise) {
                                Log.d("MainActivity", percentage.toString())
                            }
                        }

                        override fun onError(error: org.maplibre.android.offline.OfflineRegionError) {
                            // If an error occurs, print to logcat
                            Log.e("MainActivity", "onError reason: " + error.reason)
                            Log.e("MainActivity", "onError message: " + error.message)
                        }

                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            // Notify if offline region exceeds maximum tile count
                            Log.e("MainActivity", "Mapbox tile count limit exceeded: $limit")
                        }
                    })
                }

                override fun onError(error: String) {
                    Log.e("MainActivity", "Error: $error")
                }
            })
    }

    override fun onRunning(running: Boolean) {
        this.running = running
        if (running) {
            navigation.addOffRouteListener(this)
            navigation.addProgressChangeListener(this)
        }
    }

    override fun onProgressChange(location: Location, routeProgress: RouteProgress) {
        if (tracking) { // centers cameraposition on updated location
            maplibreMap.locationComponent.forceLocationUpdate(location)
            var cameraPosition: org.maplibre.android.camera.CameraPosition =
                org.maplibre.android.camera.CameraPosition.Builder()
                    .zoom(15.0)
                    .target(
                        org.maplibre.android.geometry.LatLng(
                            location.latitude,
                            location.longitude
                        )
                    )
                    .bearing(location.bearing.toDouble())
                    .build()
            maplibreMap.animateCamera(
                org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                    cameraPosition
                ), 2000
            )
        }
        instructionView.updateDistanceWith(routeProgress)
    }

    override fun onMilestoneEvent(
        routeProgress: RouteProgress,
        instruction: String,
        milestone: Milestone
    ) {
        instructionView.updateBannerInstructionsWith(milestone)
    }

    override fun userOffRoute(location: Location) {
        own_location =
            org.maplibre.android.geometry.Point.fromLngLat(location.longitude, location.latitude)

        // Flag that device is off route
        Snackbar.make(progress_bar, "Device off-route. Re-routing..", Snackbar.LENGTH_SHORT).show()

        //recalculate route and start navigation
        navigation.stopNavigation()

        // build route and re-start navigation
        build_nav_route()
    }

    // Initiate the offline routing tiles download
    private fun init_offline_routing_tiles() {
        // create folder to store offline routing tiles
        val folder_main = "Offline_Navigation"
        val f = File(
            applicationContext.filesDir,
            folder_main
        )
        if (!f.exists()) {
            f.mkdirs()
        }
        offlineRouter = MapboxOfflineRouter(f.toPath().toString())

        offlineRouter.fetchAvailableTileVersions(ACCESS_TOKEN, object :
            OnTileVersionsFoundCallback {
            override fun onVersionsFound(availableVersions: List<String>) {
                // Choose the latest version
                versioncode = availableVersions[availableVersions.count() - 1]

                // Start offline routing tiles download
                downloadtiles(versioncode)
            }

            override fun onError(error: OfflineError) {
                Toast.makeText(
                    applicationContext,
                    "Unable to download tiles" + error.message,
                    Toast.LENGTH_LONG
                ).show()
            }
        })
    }

    // Downloading offline routing tiles
    private fun downloadtiles(versionString: String) {
        val builder = OfflineTiles.builder()
            .accessToken(ACCESS_TOKEN)
            .version(versionString)
            .boundingBox(boundingBox)

        // Display the download progress bar
        progress_bar.visibility = View.VISIBLE

        //start download
        offlineRouter.downloadTiles(builder.build(), object : RouteTileDownloadListener {
            override fun onError(error: OfflineError) {
                // Will trigger if an error occurs during the download
                // Show a toast
                Toast.makeText(
                    this@MainActivity,
                    "Error downloading nav data" + error.message,
                    Toast.LENGTH_LONG
                ).show()
                // hide the download progress bar
                progress_bar.visibility = View.INVISIBLE
            }

            override fun onProgressUpdate(percent: Int) {
                // Update progress bar
                progress_bar.setProgress(percent, true)
                Log.d("download percent", percent.toString())
            }

            override fun onCompletion() {
                // hide the download progress bar
                progress_bar.visibility = View.INVISIBLE

                try {
                    configureOfflineRouter() // configure offline router. Offline Routing tiles should already be downloaded.
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error configuring offline router: " + e.message)
                }
            }
        })
    }

    // Configuring the OfflineRouter
    private fun configureOfflineRouter() {
        // create folder to store offline routing tiles
        val folder_main = "Offline_Navigation"
        val f = File(
            applicationContext.filesDir,
            folder_main
        )
        if (!f.exists()) {
            f.mkdirs()
        }

        // versioncode == folder name 1 level below f
        var files = f.listFiles() //is file list
        for (item in files) {
            if (item.isDirectory) {
                var files2 = item.listFiles()
                for (item2 in files2) {
                    if (item2.isDirectory) {
                        versioncode = item2.name
                    }
                }
            }
        }

        offlineRouter.configure(versioncode, object : OnOfflineTilesConfiguredCallback {
            override fun onConfigured(numberOfTiles: Int) {
                Log.d("MainActivity", "Offline tiles configured: $numberOfTiles")
                // Ready
                configured_offline_route = 1
            }

            override fun onConfigurationError(error: OfflineError) {
                Log.e("Embed nav", "Offline tiles configuration error: ${error.message}")
                // Not ready
                configured_offline_route = 0
            }
        })
    }

    private fun createOfflineRoute() {
        val onlineRouteBuilder = NavigationRoute.builder(this)
            .origin(own_location!!)
            .destination(destination!!)
            .accessToken(ACCESS_TOKEN)

        val offlineRoute = OfflineRoute.builder(onlineRouteBuilder).build()
        offlineRouter.findRoute(offlineRoute, object : OnOfflineRouteFoundCallback {
            override fun onRouteFound(route: DirectionsRoute) {
                // Start navigation with route
                try {
                    navigationMapRoute!!.addRoute(route)
                    tracking = true
                    navigation.startNavigation(route)
                } catch (e: Exception) {
                    Toast.makeText(
                        this@MainActivity,
                        "Invalid Route: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onError(error: OfflineError) {
                Toast.makeText(
                    this@MainActivity,
                    "Error find route offline: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
                // Handle route error
            }
        })
    }
}