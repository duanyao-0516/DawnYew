package com.dawnyew.storage

import androidx.room.Room

fun createDatabase(path: String): DawnYewDatabase = database(Room.databaseBuilder<DawnYewDatabase>(path))
