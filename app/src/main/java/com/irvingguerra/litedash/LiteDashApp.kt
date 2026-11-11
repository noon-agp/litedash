package com.irvingguerra.litedash

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.service.notification.StatusBarNotification
import com.irvingguerra.litedash.feature.updater.Updater
import com.irvingguerra.litedash.core.vehicle.VehicleBus
import com.irvingguerra.litedash.feature.obd.ObdRepository
import com.irvingguerra.litedash.feature.tpms.CarTires
import com.irvingguerra.litedash.feature.tpms.TpmsRepository
import com.irvingguerra.litedash.feature.tpms.UsbAttachActivity
import com.irvingguerra.litedash.feature.apps.AppRepository
import com.irvingguerra.litedash.feature.car.CarRepository
import com.irvingguerra.litedash.feature.link.PhoneLink
import com.irvingguerra.litedash.feature.link.ScreenInfo
import com.irvingguerra.litedash.feature.music.MusicRepository
import com.irvingguerra.litedash.feature.music.NotificationListener
import com.irvingguerra.litedash.feature.music.NotificationService
import com.irvingguerra.litedash.feature.navigation.NavigationRepository
import com.irvingguerra.litedash.feature.dashboard.DashboardStore
import com.irvingguerra.litedash.feature.dashboard.DashboardState

/**
 * Las dependencias viven aquí (no en la actividad) porque también las usan la pantalla
 * dividida y el servicio de avisos (este solo si ya existen). Todo es perezoso.
 */
class LiteDashApp : Application() {
    // Se arma la primera vez que algo de la pantalla lo pide (no por el servicio de avisos).
    private val dependenciesLazy = lazy {
        val mapPackages = NAVIGATION_PACKAGES.toSet() +
            if (BuildConfig.DEBUG) setOf(NavigationRepository.TEST_PACKAGE) else emptySet()
        val prefs = Preferences(this)
        val bus = VehicleBus()
        val music = MusicRepository(this).apply { adoptPreferred(prefs.musicApp); opener = SplitScreen::open
            returnHome = { MainActivity.bringToFront(this@LiteDashApp) } }
        OwnPlayer.wire(this, music, prefs)
        music.migrateUsbApp()
        music.fixBluetoothLink()
        val cars = CarRepository(this, BuildConfig.CATALOG_URL)
        Dependencies(
            apps = AppRepository(this).apply { opener = SplitScreen::open },
            music = music,
            navigation = NavigationRepository(this, mapPackages),
            prefs = prefs,
            dashboard = DashboardState(DashboardStore(this)),
            cars = cars,
            updater = Updater(this, BuildConfig.UPDATES_URL, BuildConfig.VERSION_CODE.toLong()),
            weather = WeatherRepository(this, prefs),
            trip = TripRepository(this, bus),
            reminders = ReminderRepository(this),
            wheel = WheelKeys(this, prefs),
            stats = Stats(this) { prefs.stats },
            pro = ProAccess(this),
            bus = bus,
            obdLazy = lazy { ObdRepository(this, bus) },
            obdSaved = { ObdRepository.hasSavedTarget(this) },
            tpmsLazy = lazy { TpmsRepository(this, bus).apply { carTires = CarTires { factoryTires(dependencies) } } },
            tpmsSaved = { TpmsRepository.hasSavedSource(this) },
            phoneLazy = lazy {
                PhoneLink(this) {
                    val car = prefs.carName ?: cars.pkg(prefs.carModel)?.name ?: contextWithLanguage(this).getString(R.string.my_car)
                    ScreenInfo(BuildConfig.VERSION_NAME, car, prefs.carModel ?: "", Build.MODEL)
                }.also { wirePhoneMusic(it, music) }
            },
        )
    }
    val dependencies: Dependencies by dependenciesLazy

    override fun onCreate() {
        super.onCreate()
        // Antes de leer cualquier ajuste (también el idioma de attachBaseContext): nombres en inglés desde la 1.4.47.
        StorageMigration.run(this)
        // Después de la migración (mueve la carpeta de los autos): mientras Android arma la pantalla.
        CarRepository.prefetch(this)
        // Los avisos llegan aquí solo si el usuario dio acceso a notificaciones (el mismo de la música).
        // Android conecta ese servicio solo (aun tras forzar el cierre): sin la app armada, nada que hacer.
        NotificationService.listener = object : NotificationListener {
            override val wanted get() = dependenciesLazy.isInitialized()
            override fun onPosted(notification: StatusBarNotification) = dependencies.navigation.posted(notification)
            override fun onRemoved(notification: StatusBarNotification) = dependencies.navigation.removed(notification)
        }
        watchUsbOffer()
    }

    /**
     * Al irse a segundo plano se recortan los cuadros del auto (lo más pesado en memoria): a un cuarto
     * si solo se ocultó la pantalla, todos si Android ya la tiene en la lista de procesos a cerrar.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || !dependenciesLazy.isInitialized()) return
        dependencies.cars.trimMemory(all = level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
    }

    // Referencia fuerte: Android guarda los oyentes de preferencias como referencias débiles.
    // Solo importan la fuente de llantas ("source"), el tipo de adaptador OBD ("type") y el módulo OBD.
    private val onUsbPrefs = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "source" || key == "type" || key == PrefKeys.OBD_MODULE) syncReceivers()
    }

    /**
     * "Usar LiteDash con este USB" solo mientras alguien lo usa: las llantas por USB o un cable
     * OBD USB guardado con el módulo prendido. Se revisa al arrancar (fuera del hilo principal:
     * lee del disco) y cada vez que cambian esos ajustes.
     */
    private fun watchUsbOffer() {
        for (file in arrayOf(TpmsRepository.PREFS, ObdRepository.PREFS, PrefKeys.FILE)) {
            getSharedPreferences(file, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(onUsbPrefs)
        }
        syncReceivers()
    }

    private fun syncReceivers() {
        Thread({ synchronized(onUsbPrefs) {
            // El receptor de "Abrir al encender" igual que su opción (por si se restauró un respaldo).
            BootLaunch.sync(this, getSharedPreferences(PrefKeys.FILE, Context.MODE_PRIVATE).getBoolean(PrefKeys.OPEN_ON_BOOT, false))
            val settings = getSharedPreferences(PrefKeys.FILE, Context.MODE_PRIVATE)
            val obdOn = !settings.contains(PrefKeys.OBD_MODULE) || settings.getBoolean(PrefKeys.OBD_MODULE, false)
            UsbAttachActivity.offer(this, TpmsRepository.usesUsb(this) || obdOn && ObdRepository.hasUsbTarget(this))
        } }, "sync-receivers").start()
    }
}
