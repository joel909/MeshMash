package com.example.meshmash

import android.app.Application
import com.example.meshmash.mesh.MeshUploadScheduler

class MeshMashApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MeshUploadScheduler.enqueue(this)
    }
}
