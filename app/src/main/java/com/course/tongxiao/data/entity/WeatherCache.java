package com.course.tongxiao.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Open-Meteo daily forecast cache (design.md §3). Stale >24h = never advance. */
@Entity(tableName = "weather_cache")
public class WeatherCache {

    @PrimaryKey
    @NonNull
    public String city = "";

    /** yyyy-MM-dd the forecast row refers to. */
    @NonNull
    public String date = "";

    @NonNull
    public String condition = "";

    public int minTemp;
    public long fetchedAt;
}
