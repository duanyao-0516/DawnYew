package com.dawnyew.storage

import android.content.Context
import androidx.room.Room

fun createDatabase(context: Context, path: String): DawnYewDatabase =
    database(Room.databaseBuilder<DawnYewDatabase>(context.applicationContext, path))
