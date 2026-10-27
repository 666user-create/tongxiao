package com.course.tongxiao.data.dao;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.course.tongxiao.data.entity.WeatherCache;

@Dao
public interface WeatherCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(WeatherCache row);

    /**
     * One row per city (PK=city): the cache always holds the freshest forecast.
     * A date mismatch means the stored row is not for the requested day → null
     * → caller must degrade to "no advance" (design.md §5.3).
     */
    @Nullable
    @Query("SELECT * FROM weather_cache WHERE city = :city AND date = :date")
    WeatherCache get(String city, String date);

    @Query("DELETE FROM weather_cache")
    void clearAll();
}
