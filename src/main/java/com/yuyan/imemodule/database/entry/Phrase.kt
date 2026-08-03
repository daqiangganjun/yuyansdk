package com.yuyan.imemodule.database.entry

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// 三个编码列是按键路径上的查询条件，无索引会导致每次击键全表扫描
@Entity(
    tableName = "phrase",
    indices = [Index("t9"), Index("qwerty"), Index("lx17")]
)
data class Phrase(
    @PrimaryKey
    @ColumnInfo(name = "content")
    var content: String,
    @ColumnInfo(name = "isKeep")
    var isKeep: Int = 0,
    @ColumnInfo(name = "t9")
    var t9: String,
    @ColumnInfo(name = "qwerty")
    var qwerty: String,
    @ColumnInfo(name = "lx17")
    var lx17: String,
    @ColumnInfo(name = "time")
    val time: Long = System.currentTimeMillis(),
)