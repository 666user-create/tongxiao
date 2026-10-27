package com.course.tongxiao.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.course.tongxiao.data.entity.WeatherCache;

@Dao
public interface WeatherCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(WeatherCache row);

    @Query("SELECT * FROM weather_cache WHERE city = :city AND date = :date")
    WeatherCache get(String city, String date);

    @Query("DELETE FROM weather_cache")
    void clearAll();
}
