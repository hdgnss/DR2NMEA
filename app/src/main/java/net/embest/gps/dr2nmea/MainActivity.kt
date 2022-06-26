/*
 * Copyright (C) 2022 HDGNSS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hdgnss.dr2nmea

import android.Manifest
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.icu.text.SimpleDateFormat
import android.location.*
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.*
import android.preference.PreferenceActivity
import android.preference.PreferenceManager
import android.provider.Settings
import android.support.design.widget.BottomNavigationView
import android.support.design.widget.Snackbar
import android.support.v4.app.ActivityCompat
import android.support.v4.app.Fragment
import android.support.v4.content.ContextCompat
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.widget.Toast.LENGTH_SHORT
import android.widget.Toast.makeText
import com.clj.fastble.BleManager
import com.clj.fastble.callback.BleNotifyCallback
import com.clj.fastble.data.BleDevice
import com.clj.fastble.exception.BleException
import com.clj.fastble.utils.HexUtil
import kotlinx.android.synthetic.main.activity_main.*
import com.hdgnss.dr2nmea.fragments.BtFragment
import com.hdgnss.dr2nmea.fragments.SensorFragment
import com.hdgnss.dr2nmea.fragments.SnrFragment
import com.hdgnss.dr2nmea.utils.*
import java.lang.Long.parseLong
import java.lang.ref.WeakReference
import java.util.*


class MainActivity : AppCompatActivity(), SensorEventListener, BtFragment.OnFragmentInteractionListener {

    private val mSnrFragment = SnrFragment.newInstance()
    private val mSensorFragment = SensorFragment.newInstance()
    private val mBtFragment = BtFragment.newInstance()

    private val mContext by lazy { this }
    private var mCurrentFragment: Int = 0

    private val mFile = FileHelper()
    private val mNmeaGenerator = NmeaGenerator()

    private var mService: LocationManager? = null
    private var mProvider: LocationProvider? = null

    private var mSensorManager: SensorManager? = null

    private var mSensorAcc: Sensor? = null
    private var mSensorGyro: Sensor? = null
    private var mSensorBaro: Sensor? = null
    private var mSensorMag: Sensor? = null
    private var mSensorUnCalMag: Sensor? = null
    private var mTimeStamp: Long = 0
    private var mSensorLog = ""

    private var mSensorUnCalAcc: Sensor? = null
    private var mSensorUnCalGyro: Sensor? = null

    private var mGnssStarted: Boolean = false
    private var mNeedExit: Boolean =false
    private var mSpeedBeepCount = 0
    private var mSensorBeepCount = 0


    private var mRecordFileName: String = "gnss_record"

    private var mPreferences: SharedPreferences? = null

    private var mGnssInfo: GnssInfo? = null

    private var mGnssStatusCallBack: GnssStatus.Callback? = null
    private var mGnssMeasurementsCallBack: GnssMeasurementsEvent.Callback? = null
    private var mGnssNavigationCallBack: GnssNavigationMessage.Callback? = null

    private var mWheelCount:Long = 0
    private var mTimestamp = System.currentTimeMillis()

    private var udpSocket: UdpSocket? = null

    private var mUdpHandler: Handler
    init {
        val outerClass = WeakReference(this)
        mUdpHandler = UdpScoketHandler(outerClass)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.e(TAG, "onCreate()")
        setContentView(R.layout.activity_main)
        //navigation.menu.removeItem(R.id.navigation_map)

        // Check the screen orientation, and keep it
        val value = resources.configuration.orientation
        requestedOrientation = if (value == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        else{
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        mGnssInfo = GnssInfo()

        val preferences = getSharedPreferences("_has_set_default_values", Context.MODE_PRIVATE)
        if (!preferences.getBoolean("_has_set_default_values", false)) {
            PreferenceManager.setDefaultValues(this, R.xml.pref_general, true)
        }

        mPreferences = PreferenceManager.getDefaultSharedPreferences(baseContext)

        navigation.setOnNavigationItemSelectedListener(mOnNavigationItemSelectedListener)
        if (!mPreferences!!.getBoolean("preference_enable_bt_speed_support",false)) {
            navigation.menu.removeItem(R.id.navigation_bt)
        }
        checkAndRequestPermissions()

        fab.setOnClickListener{ view ->
            mGnssStarted = if (!mGnssStarted) {
                onMakeRecordName()
                mGnssInfo!!.reset()
                gpsStart(1000,0f)
                fab.setImageResource(android.R.drawable.ic_media_pause)
                Snackbar.make(view, mContext.resources.getString(R.string.msg_snack_bar_start_test), Snackbar.LENGTH_SHORT)
                    .setAction(mContext.resources.getString(R.string.msg_snack_bar_action), null).show()
                true
            } else {
                gpsStop()
                Snackbar.make(view, mContext.resources.getString(R.string.msg_snack_bar_stop_test), Snackbar.LENGTH_SHORT)
                    .setAction(mContext.resources.getString(R.string.msg_snack_bar_action), null).show()
                fab.setImageResource(android.R.drawable.ic_media_play)
                false
            }
        }
        udpSocket = UdpSocket(mUdpHandler)
        udpSocket?.startUDPSocket()
    }

    override fun onResume() {
        Log.e(TAG, "onResume()")
        super.onResume()
        //Keep screen
        if (mPreferences!!.getBoolean("preference_keep_screen",true)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        //Reset the test status
        if (!mGnssStarted) {
            fab.setImageResource(android.R.drawable.ic_media_play)
        } else {
            fab.setImageResource(android.R.drawable.ic_media_pause)
        }
    }


    override fun onPause(){
        super.onPause()
        Log.e(TAG, "onPause:$mCurrentFragment")
    }


    override fun onDestroy() {
        super.onDestroy()
        Log.e(TAG, "onDestroy()")
        unregisterCallbacks()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> {
                val intent = Intent(mContext, SettingsActivity::class.java)
                intent.putExtra( PreferenceActivity.EXTRA_SHOW_FRAGMENT, SettingsActivity.GeneralPreferenceFragment::class.java.name )
                intent.putExtra( PreferenceActivity.EXTRA_NO_HEADERS, true )
                startActivity(intent)
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        if (mNeedExit) {
            finish()
        } else {
            makeText(this, mContext.resources.getString(R.string.msg_exit_app), LENGTH_SHORT).show()
            mNeedExit = true
            Handler().postDelayed({ mNeedExit = false }, (3 * 1000).toLong())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        when (sensor!!.type){
            Sensor.TYPE_ACCELEROMETER -> {
                Log.e(TAG, "ACCCAL:$accuracy")
            }
            Sensor.TYPE_GYROSCOPE -> {
                Log.e(TAG, "GYRCAL:$accuracy")
            }
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> {
                Log.e(TAG, "ACCORG:$accuracy")
                onUpdateSensorAcc(sensor.type, accuracy)
            }
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> {
                Log.e(TAG, "GYRORG:$accuracy")
                onUpdateSensorAcc(sensor.type, accuracy)
            }
            Sensor.TYPE_PRESSURE -> {
                Log.e(TAG, "BARORG:$accuracy")
                onUpdateSensorAcc(sensor.type, accuracy)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                Log.e(TAG, "MAGCAL:$accuracy")
            }
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> {
                Log.e(TAG, "MAGORG:$accuracy")
                onUpdateSensorAcc(sensor.type, accuracy)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val tsLong = System.currentTimeMillis()
        when (event.sensor!!.type){
            Sensor.TYPE_ACCELEROMETER -> {
                mSensorLog += "ACCCAL,$tsLong,X:${event.values[0]},Y:${event.values[1]},Z:${event.values[2]}\r\n"
            }
            Sensor.TYPE_GYROSCOPE -> {
                mSensorLog += "GYRCAL,$tsLong,X:${event.values[0]},Y:${event.values[1]},Z:${event.values[2]}\r\n"
            }
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> {
                mSensorLog += "ACCORG,$tsLong,X:${event.values[0]},Y:${event.values[1]},Z:${event.values[2]}\r\n"
                onUpdateSensorView(tsLong,event.values[0],event.values[1],event.values[2],event.sensor!!.type)
            }
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> {
                mSensorLog += "GYRORG,$tsLong,X:${event.values[0]},Y:${event.values[1]},Z:${event.values[2]}\r\n"
                onUpdateSensorView(tsLong,event.values[0],event.values[1],event.values[2],event.sensor!!.type)
            }
            Sensor.TYPE_PRESSURE -> {
                mSensorLog += "BARORG,$tsLong,V:${event.values[0]}\r\n"
                onUpdateSensorView(tsLong,event.values[0],0f,0f,event.sensor!!.type)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                mSensorLog += "MAGCAL,$tsLong,V:${event.values[0]}\r\n"
            }
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> {
                mSensorLog += "MAGORG,$tsLong,V:${event.values[0]}\r\n"
                onUpdateSensorView(tsLong,event.values[0],0f,0f,event.sensor!!.type)
            }
        }
        if (mPreferences!!.getBoolean("preference_sensor_record", true)) {
            if(tsLong - mTimeStamp >= 1000){
                mTimeStamp = tsLong
                mFile.writeSensorFile(mRecordFileName, mSensorLog)
                mSensorLog = ""
            }
        }else{
            mSensorLog = ""
        }
    }

    override fun onBtDeviceNotfiy(bleDevice: BleDevice, characteristic: BluetoothGattCharacteristic){
        BleManager.getInstance().notify(bleDevice, characteristic.service.uuid.toString(), characteristic.uuid.toString(), object : BleNotifyCallback() {
                override fun onNotifySuccess() {
                    runOnUiThread(Runnable { Log.e("BTTTT", "notify success") })
                }

                override fun onNotifyFailure(exception: BleException) {
                    runOnUiThread(Runnable {
                        Log.e("BTTTT", exception.toString() )
                    })
                }

                override fun onCharacteristicChanged(data: ByteArray) {
                    val timestamp = System.currentTimeMillis()
                    runOnUiThread(Runnable {
                        val countHex = HexUtil.formatHexString(characteristic.value,true ).split(" ")
                        //Log.e("BTTTT:", "${countHex[1]} ${countHex[2]} ${countHex[3]} ${countHex[4]}")
                        val count:Long = parseLong(countHex[1], 16) + parseLong(countHex[2], 16)*256 + parseLong(countHex[3], 16)*65536 + parseLong(countHex[4], 16)*16777216

                        var speed = 0.0f
                        var speer_unc = 0
                        if (mWheelCount>0){
                            if (mWheelCount == count){
                                speed = 0.0f
                                Log.e("Speed:", "Speed is $speed")
                            }else{
                                val delta = count - mWheelCount
                                val wheel = mPreferences!!.getString("preference_wheel_circumference", "1.0")!!.toFloat()
                                speed = delta*wheel/(timestamp-mTimestamp)*1000.0f
                                Log.e("Speed:", "Speed is $speed")
                                val pglor = "\$pglor13,$timestamp,"+String.format("%.2f", speed)+ ",5,0"
                                udpSocket?.sendMessage(pglor)
                            }
                        }

                        mSensorLog += "SPEED,$timestamp,V:$speed\r\n"
                        onUpdateSpeedView(timestamp,speed,0f,0f, 0)
                        mTimestamp = timestamp
                        mWheelCount = count

                    })
                }
            })
    }

    class UdpScoketHandler(private val outerClass: WeakReference<MainActivity>) : Handler() {
        override fun handleMessage(msg: Message?) {
            Log.e(TAG, "UDP:" + msg)
        }
    }

    private fun replaceFragment(fragment: Fragment) {
        val fragmentTransaction = supportFragmentManager.beginTransaction()
        fragmentTransaction.replace(R.id.container, fragment)
        fragmentTransaction.commitNow()
        if(mGnssInfo!!.satellites.isNotEmpty()){
            onUpdateView()
        }
    }

    private val mOnNavigationItemSelectedListener = BottomNavigationView.OnNavigationItemSelectedListener { item ->
        when (item.itemId) {
            R.id.navigation_snr -> {
                mCurrentFragment = SNR_FRAGMENT
                replaceFragment(mSnrFragment)
                return@OnNavigationItemSelectedListener true
            }
            R.id.navigation_sen -> {
                mCurrentFragment = SEN_FRAGMENT
                replaceFragment(mSensorFragment)
                return@OnNavigationItemSelectedListener true
            }
            R.id.navigation_bt ->{
                mCurrentFragment = BT_FRAGMENT
                replaceFragment(mBtFragment)
                return@OnNavigationItemSelectedListener true
            }
        }
        false
    }


    private fun  onMakeRecordName() {
        val lTime = System.currentTimeMillis()

        val time = SimpleDateFormat("yyMMdd_HHmmss", Locale.US)

        val device = "${Build.MODEL}_${mPreferences!!.getString("preference_device_name", "01")}"

        mRecordFileName = device + "_" + time.format(lTime)

        mRecordFileName = mRecordFileName.replace("-","_")
        mRecordFileName = mRecordFileName.replace(" ", "_")
        mRecordFileName = mRecordFileName.replace(".", "")

        Log.i(TAG, "Name:$mRecordFileName")
    }


    private fun onUpdatePermissions() {
        mService = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        mSensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        if (Build.VERSION.SDK_INT >= 28) {
            Log.e(TAG, "gnssHardwareModelName: " + mService!!.gnssHardwareModelName + mService!!.gnssYearOfHardware )
        }

        mProvider = mService!!.getProvider(LocationManager.GPS_PROVIDER)

        mSensorAcc = mSensorManager!!.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        mSensorGyro = mSensorManager!!.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        mSensorBaro = mSensorManager!!.getDefaultSensor(Sensor.TYPE_PRESSURE)
        mSensorUnCalAcc = mSensorManager!!.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED)
        mSensorUnCalGyro = mSensorManager!!.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
        mSensorMag = mSensorManager!!.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        mSensorUnCalMag = mSensorManager!!.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)


        if (mProvider == null) {
            Log.e(TAG, "Unable to get GPS_PROVIDER")
            makeText(this, getString(R.string.msg_gps_not_supported), LENGTH_SHORT).show()
            finish()
        }

        BleManager.getInstance().init(application)
        BleManager.getInstance().enableLog(true)
            .setReConnectCount(1, 5000)
            .setConnectOverTime(20000).operateTimeout = 5000

        replaceFragment(mSnrFragment)
    }

    private fun checkAndRequestPermissions(): Boolean {
        val permissionWrite = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val permissionLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)

        val listPermissionsNeeded = ArrayList<String>()

        if (permissionWrite != PackageManager.PERMISSION_GRANTED) {
            listPermissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (permissionLocation != PackageManager.PERMISSION_GRANTED) {
            listPermissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, listPermissionsNeeded.toTypedArray(), REQUEST_ID_MULTIPLE_PERMISSIONS)
            return false
        }
        onUpdatePermissions()
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        Log.d(TAG, "Permission callback called")
        when (requestCode) {
            REQUEST_ID_MULTIPLE_PERMISSIONS -> {
                val perms = HashMap<String, Int>()
                perms[Manifest.permission.WRITE_EXTERNAL_STORAGE] = PackageManager.PERMISSION_GRANTED
                perms[Manifest.permission.ACCESS_FINE_LOCATION] = PackageManager.PERMISSION_GRANTED
                if (grantResults.isNotEmpty()) {
                    for (i in permissions.indices)
                        perms[permissions[i]] = grantResults[i]
                    if ( perms[Manifest.permission.WRITE_EXTERNAL_STORAGE] == PackageManager.PERMISSION_GRANTED
                        && perms[Manifest.permission.ACCESS_FINE_LOCATION] == PackageManager.PERMISSION_GRANTED) {
                        Log.e(TAG, "storage & location services permission granted")
                        onUpdatePermissions()
                    } else {
                        Log.d(TAG, "Some permissions are not granted ask again ")
                        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            || ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
                            val dialog = AlertDialog.Builder(this)
                            dialog.setMessage(R.string.msg_request_permissions)
                                .setPositiveButton(R.string.msg_dialog_yes) { _, _ -> checkAndRequestPermissions() }
                                .setNegativeButton(R.string.msg_dialog_cancel) { _, _ -> finish() }
                            dialog.show()
                        } else {
                            val dialog = AlertDialog.Builder(this)
                            dialog.setMessage(R.string.msg_request_permissions_settings)
                                .setPositiveButton(R.string.msg_dialog_yes) { _, _ ->
                                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:com.hdgnss.gnsstest")))
                                }
                                .setNegativeButton(R.string.msg_dialog_cancel) { _, _ -> finish() }
                            dialog.show()
                        }
                    }
                }
            }
        }
    }




    private fun onUpdateView() {
        when (mCurrentFragment) {
            SNR_FRAGMENT  -> mSnrFragment.onUpdateView(mGnssInfo!!)
//            MAP_FRAGMENT  -> mMapFragment.onUpdateView(mGnssInfo!!)
        }
    }


    private fun onUpdateSensorView(time :Long, x: Float,y: Float,z: Float, type: Int) {

        when (mCurrentFragment) {
            SEN_FRAGMENT  -> {
                mSensorFragment.onUpdateView(time, x, y, z, type)
            }
        }
    }

    private fun onUpdateSpeedView(time :Long, x: Float,y: Float,z: Float, type: Int) {
        when (mCurrentFragment) {
            BT_FRAGMENT  -> {
                mBtFragment.onUpdateView(time, x, y, z, type)
            }
        }
    }

    private fun onUpdateSensorAcc(type: Int, accuracy: Int) {

        when (mCurrentFragment) {
            SEN_FRAGMENT  -> {
                mSensorFragment.onUpdateAccuracy(type, accuracy)
            }
        }
    }

    @Synchronized private fun gpsStart(minTime: Long, minDis:Float) {
        if (!mGnssStarted) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                mService!!.requestLocationUpdates(mProvider!!.name, minTime, minDis, mLocationListener)
                mGnssStarted = true
                registerGnssStatusCallback()
                registerGnssMeasurementsCallback()
                registerGnssNavigationMessageCallback()
                registerNmeaMessageListener()
            }

            mSensorManager!!.registerListener(this, mSensorAcc, SensorManager.SENSOR_DELAY_GAME)
            mSensorManager!!.registerListener(this, mSensorGyro, SensorManager.SENSOR_DELAY_GAME)
            mSensorManager!!.registerListener(this, mSensorBaro, SensorManager.SENSOR_DELAY_GAME)
            mSensorManager!!.registerListener(this, mSensorUnCalAcc, SensorManager.SENSOR_DELAY_GAME)
            mSensorManager!!.registerListener(this, mSensorUnCalGyro, SensorManager.SENSOR_DELAY_GAME)
            mSensorManager!!.registerListener(this, mSensorMag, SensorManager.SENSOR_DELAY_GAME)
            mSensorManager!!.registerListener(this, mSensorUnCalMag, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    @Synchronized private fun gpsStop() {
        if (mGnssStarted) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                mService!!.removeUpdates(mLocationListener)
                mGnssStarted = false
            }
            mSensorManager!!.unregisterListener(this)
        }
        unregisterCallbacks()
    }


    private val mLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            mGnssInfo!!.accuracy = location.accuracy
            mGnssInfo!!.speed = location.speed
            mGnssInfo!!.altitude = location.altitude
            mGnssInfo!!.latitude = location.latitude
            mGnssInfo!!.longitude = location.longitude
            mGnssInfo!!.bearing = location.bearing

            if (mGnssInfo!!.time > 0){
                mGnssInfo!!.fixtime = (location.time - mGnssInfo!!.time).toFloat() / 1000
            }
            mGnssInfo!!.time = location.time
            Log.d(TAG, "onLocationChanged ${mGnssInfo!!.latitude}  ${mGnssInfo!!.longitude}  ${mGnssInfo!!.altitude} ${mGnssInfo!!.fixtime}")
            //Log.e(TAG, "onLocationChanged: ${mNmeaGenerator.onGenerateNmea(mGnssInfo!!)}")

            if (mPreferences!!.getBoolean("preference_nmea_generator", true)) {
                mFile.writeGeneratorNmea(mRecordFileName, mNmeaGenerator.onGenerateNmea(mGnssInfo!!))
            }
        }

        override fun onProviderDisabled(provider: String) {
            Log.d(TAG, "LocationListener onProviderDisabled")
        }

        override fun onProviderEnabled(provider: String) {
            Log.i(TAG, "LocationListener onProviderEnabled")
        }

        override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {
            Log.e(TAG, "LocationListener onStatusChanged")
        }
    }

    private fun registerGnssStatusCallback(){
        if (mGnssStatusCallBack == null) {
            mGnssStatusCallBack = object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    mGnssInfo!!.cleanSatellites()
                    var use = 0
                    var view = 0
                    val list: ArrayList<String> = ArrayList()
                    for (i in 0 until status.satelliteCount) {
                        if (status.getCn0DbHz(i) > 0) {
                            val satellite = GnssSatellite()
                            satellite.azimuths = status.getAzimuthDegrees(i)
                            satellite.cn0 = status.getCn0DbHz(i)
                            satellite.constellation = status.getConstellationType(i)
                            satellite.svid = status.getSvid(i)
                            satellite.inUse = status.usedInFix(i)
                            satellite.elevations = status.getElevationDegrees(i)
                            satellite.frequency = status.getCarrierFrequencyHz(i)

                            if (satellite.frequency == 0.0f)
                            {
                                val sat = String.format("%d%4d)", satellite.constellation, satellite.svid)
                                if (list.indexOf(sat) < 0){
                                    list.add(sat)
                                }else{
                                    satellite.frequency = GnssSatellite.GPS_L5_FREQUENCY
                                }
                            }

                            mGnssInfo!!.addSatellite(satellite)
                            view++
                            if (status.usedInFix(i)) {
                                use++
                            }
                        }
                    }

                    mGnssInfo!!.inuse = use
                    mGnssInfo!!.inview = view
                    onUpdateView()
                }

                override fun onFirstFix(ttffMillis: Int) {
                    mGnssInfo!!.ttff = ttffMillis/1000f
                    Log.e(TAG, "TTFF: ${mNmeaGenerator.onGenerateFIX(mGnssInfo!!)}")
                }
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                mService!!.registerGnssStatusCallback(mGnssStatusCallBack)
            }
        }
    }

    private fun registerGnssMeasurementsCallback(){
        if (mGnssMeasurementsCallBack == null) {
            mGnssMeasurementsCallBack = object : GnssMeasurementsEvent.Callback() {
                @Suppress("DEPRECATION")
                override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                    for (meas in event.measurements) {
                        val measurementStream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            "Raw,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\r\n".format(
                                SystemClock.elapsedRealtime(),
                                event.clock.timeNanos,
                                event.clock.leapSecond,
                                event.clock.timeUncertaintyNanos,
                                event.clock.fullBiasNanos,
                                event.clock.biasNanos,
                                event.clock.biasUncertaintyNanos,
                                event.clock.driftNanosPerSecond,
                                event.clock.driftUncertaintyNanosPerSecond,
                                event.clock.hardwareClockDiscontinuityCount,
                                meas.svid,
                                meas.timeOffsetNanos,
                                meas.state,
                                meas.receivedSvTimeNanos,
                                meas.receivedSvTimeUncertaintyNanos,
                                meas.cn0DbHz,
                                meas.pseudorangeRateMetersPerSecond,
                                meas.pseudorangeRateUncertaintyMetersPerSecond,
                                meas.accumulatedDeltaRangeState,
                                meas.accumulatedDeltaRangeMeters,
                                meas.accumulatedDeltaRangeUncertaintyMeters,
                                meas.carrierFrequencyHz,
                                meas.carrierCycles,
                                meas.carrierPhase,
                                meas.carrierPhaseUncertainty,
                                meas.multipathIndicator,
                                meas.snrInDb,
                                meas.constellationType,
                                meas.automaticGainControlLevelDb,
                                meas.carrierFrequencyHz).replace("NaN","")

                        } else {
                            "Raw,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\r\n".format(
                                SystemClock.elapsedRealtime(),
                                event.clock.timeNanos,
                                event.clock.leapSecond,
                                event.clock.timeUncertaintyNanos,
                                event.clock.fullBiasNanos,
                                event.clock.biasNanos,
                                event.clock.biasUncertaintyNanos,
                                event.clock.driftNanosPerSecond,
                                event.clock.driftUncertaintyNanosPerSecond,
                                event.clock.hardwareClockDiscontinuityCount,
                                meas.svid,
                                meas.timeOffsetNanos,
                                meas.state,
                                meas.receivedSvTimeNanos,
                                meas.receivedSvTimeUncertaintyNanos,
                                meas.cn0DbHz,
                                meas.pseudorangeRateMetersPerSecond,
                                meas.pseudorangeRateUncertaintyMetersPerSecond,
                                meas.accumulatedDeltaRangeState,
                                meas.accumulatedDeltaRangeMeters,
                                meas.accumulatedDeltaRangeUncertaintyMeters,
                                meas.carrierFrequencyHz,
                                meas.carrierCycles,
                                meas.carrierPhase,
                                meas.carrierPhaseUncertainty,
                                meas.multipathIndicator,
                                meas.snrInDb,
                                meas.constellationType,
                                "",
                                meas.carrierFrequencyHz).replace("NaN","")
                        }
                        if (mPreferences!!.getBoolean("preference_raw_record", true)) {
                            mFile.writeMeasurementFile(mRecordFileName,measurementStream)
                        }
                    }
                }

                override fun onStatusChanged(status: Int) {}
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mService!!.registerGnssMeasurementsCallback(mGnssMeasurementsCallBack)
        }
    }

    private fun registerGnssNavigationMessageCallback(){
        if (mGnssNavigationCallBack == null) {
            mGnssNavigationCallBack = object : GnssNavigationMessage.Callback() {
                override fun onGnssNavigationMessageReceived(event: GnssNavigationMessage) {
                    //Log.i(TAG, "NAV : " + event.toString())
                }
                override fun onStatusChanged(status: Int) {}
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mService!!.registerGnssNavigationMessageCallback(mGnssNavigationCallBack)
        }
    }

    private fun onSpeedBeep()
    {
        if (mSpeedBeepCount == 0) {
            val notification: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r: Ringtone = RingtoneManager.getRingtone(applicationContext, notification)
            r.play()
        }
        mSpeedBeepCount = (mSpeedBeepCount+1)%10
    }

    private fun onSensorBeep()
    {
        if (mSensorBeepCount == 0) {
            val notification: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r: Ringtone = RingtoneManager.getRingtone(applicationContext, notification)
            r.play()
            r.play()
        }
        mSensorBeepCount = (mSensorBeepCount+1)%10
    }

    private fun registerNmeaMessageListener(){
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

            mService!!.addNmeaListener(OnNmeaMessageListener { message, _ ->
                if (mPreferences!!.getBoolean("preference_nmea_record", true)) {
                    mFile.writeNmeaFile(mRecordFileName, message)
                }
                if (message.indexOf("HLA") > 0) {
                    val messageList = message.split(',')
                    if (messageList[16] == "1" && messageList[18] == "1") {
                        onSensorBeep()
                    }
                    if (messageList[20].isNotEmpty()) {
                        if (messageList[20].toFloat() > 0) {
//                            onSpeedBeep()
                            onSensorBeep()
                        }
                    }
                }
            })
        }
    }

    private fun unregisterCallbacks() {
        if (mGnssStatusCallBack != null) {
            mService!!.unregisterGnssStatusCallback(mGnssStatusCallBack)
            mGnssStatusCallBack = null
        }

        if (mGnssMeasurementsCallBack != null) {

            mService!!.unregisterGnssMeasurementsCallback(mGnssMeasurementsCallBack)
            mGnssMeasurementsCallBack = null
        }

        if (mGnssNavigationCallBack != null) {

            mService!!.unregisterGnssNavigationMessageCallback(mGnssNavigationCallBack)
            mGnssNavigationCallBack = null
        }
    }


    companion object {
        private const val TAG = "DR2NMEA"

        private const val REQUEST_ID_MULTIPLE_PERMISSIONS = 1
        private const val SNR_FRAGMENT  = 0
        private const val SEN_FRAGMENT  = 1
        private const val BT_FRAGMENT  = 2

    }

}
