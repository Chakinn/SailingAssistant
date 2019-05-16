package com.example.maciej.sailingassistant

import android.app.DatePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.support.v4.content.LocalBroadcastManager
import android.util.Log
import android.view.View
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.PolylineOptions
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.max
import kotlin.math.min


class MainActivity : AppCompatActivity(), OnMapReadyCallback, GoogleMap.OnMapClickListener {

    lateinit var map: GoogleMap
    private lateinit var mapView: MapView
    private val firebaseDatabaseManager = FirebaseDatabaseManager
    var startDatetime: Datetime = Datetime(0, 0, 0, 0, 0, 0, 0)
    var endDatetime: Datetime = Datetime(0, 0, 0, 0, 0, 0, 0)
    var pointList = ArrayList<Point?>()
    val numberOfNeighbors = 300
    private val firebaseCallback = object : FirebaseCallback {
        override fun onCallback(points: ArrayList<Point?>) {
            pointList = points
            map.clear()
            if (!pointList.isEmpty()) {
                val startLatitude = points[0]?.latitude
                val startLongitude = points[0]?.longitude
                val startPosition = LatLng(startLatitude ?: 0.0, startLongitude ?: 0.0)
                map.moveCamera(CameraUpdateFactory.newLatLng(startPosition))
                for (i in 0..points.size - 2) {   //robocze rysowanie trasy
                    map.addPolyline(PolylineOptions().add(LatLng(points[i]!!.latitude, points[i]!!.longitude), LatLng(points[i + 1]!!.latitude, points[i + 1]!!.longitude)).width(5.0f).color(Color.BLUE))
                }
            }
        }
    }

    /**
     * Przeszukuje listę punktów w poszukiwaniu najbliższego kliknięciu.
     * Następnie uruchami aktywność z podglądem detali i przekazuje jej najbliższych (300) sąsiadów wybranego punktu
     */
    override fun onMapClick(p0: LatLng?) {
        if (p0 == null) {
            return
        }
        val i = findClosestPointIndex(p0.latitude, p0.longitude)
        if (i == null) {
            Log.d("sailor", "no points")
            return
        }
        Log.d("sailor", "(${pointList[i]!!.latitude},${pointList[i]!!.longitude})")

        val neighbourPoints = ArrayList<Point>(numberOfNeighbors + 1)
        for (j in max(0, i - numberOfNeighbors / 2)..min(pointList.lastIndex, i + numberOfNeighbors / 2)) {
            neighbourPoints.add(pointList[j]!!)
        }
        launchDetailActivity(neighbourPoints)
    }

    override fun onMapReady(map: GoogleMap?) {
        if (map != null) this.map = map
        map?.setMinZoomPreference(16.0f)
        map?.setOnMapClickListener(this)
        firebaseDatabaseManager.fetchPoints(startDatetime, endDatetime, firebaseCallback)
    }

    /**
     * Zwraca pozycję na liście punktu najbliższego danym koordynatom
     * Zwraca null gdy lista punktów jest pusta
     */
    fun findClosestPointIndex(targetLatitude: Double, targetLongitude: Double): Int? {
        if (pointList.isEmpty()) {
            return null
        }
        var closestPointIndex = 0
        var minDistance = distance(targetLatitude, targetLongitude, pointList[0]!!.latitude, pointList[0]!!.longitude)
        for ((i, point) in pointList.withIndex()) {
            val dist = distance(targetLatitude, targetLongitude, point!!.latitude, point.longitude)
            if (dist < minDistance) {
                closestPointIndex = i
                minDistance = dist
            }
        }
        return closestPointIndex
    }

    /**
     * Zwraca odległość między punktami (latitude1,longitude1)  (latitude2,longitude2) na kuli ziemskiej
     */
    private val earthRadius = 6371000f

    private fun distance(latitude1: Double, longitude1: Double, latitude2: Double, longitude2: Double): Double {
        val lat1 = latitude1.toRadian()
        val lat2 = latitude2.toRadian()
        val dlat = (lat2 - lat1)
        val dlong = (longitude2 - longitude1).toRadian()

        val a = Math.sin(dlat / 2) * Math.sin(dlat / 2) + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dlong / 2) * Math.sin(dlong / 2)
        return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    /**
     * Zamiana stopni na radiany
     */
    private fun Double.toRadian(): Double {
        return this * Math.PI / 180f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        //Odczytaj jaki przedział czasu był potrzebny ostatnio
        val preferences = getPreferences(Context.MODE_PRIVATE)
        with(preferences) {
            startDatetime = Datetime.fromString(getString("START_DATETIME", Datetime.zeroDatetimeString)!!)
            endDatetime = Datetime.fromString(getString("END_DATETIME", Datetime.zeroDatetimeString)!!)
        }

        mapView = findViewById(R.id.map_view)
        mapView.onCreate(null)
        mapView.getMapAsync(this)


        LocalBroadcastManager.getInstance(this).registerReceiver(mReceiver, IntentFilter("center_intent"))  //do odbioru śr punktu i kierunku

    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onStop() {
        //Zapisz wybrany przedział czasu do preferencji
        val preferences = getPreferences(Context.MODE_PRIVATE)
        with(preferences.edit()) {
            putString("START_DATETIME", startDatetime.toFormattedString())
            putString("END_DATETIME", endDatetime.toFormattedString())
            apply()
        }
        super.onStop()
        mapView.onStop()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }


    /**
     * Uruchamia aktywność z podglądem szczegółów i przekuzje jej listę punktów
     */
    fun launchDetailActivity(points: ArrayList<Point>) {
        val detailIntent = Intent(this, DetailActivity::class.java)
        detailIntent.putParcelableArrayListExtra("points", points)
        startActivity(detailIntent)
    }

    /**
     * Wyświetla dialog z wyborem daty i pobiera dane z wybranego przez użytkownika dnia
     */
    fun showDatePickerDialog(view: View) {
        val calendar = Calendar.getInstance()
        val dateDialog = DatePickerDialog(this, DatePickerDialog.OnDateSetListener { _, year, month, dayOfMonth ->
            startDatetime = Datetime(year, month + 1, dayOfMonth, 0, 0, 0, 0)
            endDatetime = Datetime(year, month + 1, dayOfMonth, 23, 59, 59, 999)
            firebaseDatabaseManager.fetchPoints(startDatetime, endDatetime, firebaseCallback)

        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH))
        dateDialog.show()
    }

    /**
     * Znajduje wcześniejsze lub następne 300 punktów
     * w zależności od danych z DetailActivity;
     * i uruchamia DetailActivity z nowymi punktami
     */

    fun findNewNeighbours(centerPointString: String, direction: String) {
        val neighbourPoints = ArrayList<Point>(numberOfNeighbors + 1)
        var shiftIndex = 0
        val newCenterPointIndex: Int

        for (i in pointList.indices) {
            if (pointList[i].toString() == centerPointString) {
                if (direction == "right") {
                    shiftIndex += numberOfNeighbors
                }
                if (direction == "left") {
                    shiftIndex -= numberOfNeighbors
                }
                newCenterPointIndex = i + shiftIndex
                if (newCenterPointIndex - numberOfNeighbors / 2 >= 0 && newCenterPointIndex + numberOfNeighbors / 2 < pointList.size) {
                    for (j in newCenterPointIndex - numberOfNeighbors / 2..newCenterPointIndex + numberOfNeighbors / 2) {
                        neighbourPoints.add(pointList[j]!!)
                    }
                } else if (newCenterPointIndex - numberOfNeighbors / 2 < 0) {   //jeśli wybrano początkowy odcinek czasu
                    for (j in 0..numberOfNeighbors) {
                        neighbourPoints.add(pointList[j]!!)     //dodaje pierwsze 300 punktów
                    }
                } else if (newCenterPointIndex + numberOfNeighbors / 2 > pointList.size) {  //jeśli wybrano ostatni odcinek czasu
                    for (j in pointList.size - numberOfNeighbors - 1..pointList.size - 1) {     //dodaje ostatnie 300 punktów
                        neighbourPoints.add(pointList[j]!!)
                    }
                }
                break
            }
        }
        launchDetailActivity(neighbourPoints)
    }


    /**
     * Odbiera dane: środkowy punkt (String) i kierunek z DetailActivity
     * i wywołuje uruchamienie DetailActivity
     */
    private val mReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val receivedCenterPoint = intent.getStringExtra("center")
            val receivedDirection = intent.getStringExtra("direction")
            Log.d("receiver1", "GOT MESSAGE 1: $receivedCenterPoint")
            Log.d("receiver2", "GOT MESSAGE 2: $receivedDirection")
            findNewNeighbours(receivedCenterPoint, receivedDirection)
        }
    }


}
