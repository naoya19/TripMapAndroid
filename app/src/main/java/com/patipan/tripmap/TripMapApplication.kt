package com.patipan.tripmap

import android.app.Application
import com.patipan.tripmap.data.TripDatabase

class TripMapApplication : Application() {
    val database by lazy { TripDatabase.create(this) }
}
