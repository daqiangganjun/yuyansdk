package com.yuyan.imemodule.database.entry

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// time 是 deleteOldest 排序子查询的依据
@Entity(tableName = "clipboard", indices = [Index("time")])
data class Clipboard(
    @PrimaryKey
    @ColumnInfo(name = "content")
    var content: String,
    @ColumnInfo(name = "isKeep")
    var isKeep: Int = 0,
    @ColumnInfo(name = "time")
    val time: Long = System.currentTimeMillis(),
)