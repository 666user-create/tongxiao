package com.course.tongxiao.ui;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.course.tongxiao.engine.region.RegionCatalog;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Region picker back-fill mapping (see {@link RegionPickerDialog#resolveIndices}).
 *
 * <p>This is the bug the region-data review caught: a municipality is stored as
 * two segments, so reopening the dialog used to jump back to the first district
 * instead of the saved one.
 */
@RunWith(AndroidJUnit4.class)
public class RegionPickerDialogTest {

    private static final String THREE_LEVEL = "{\"v\":1,\"provinces\":["
            + "{\"n\":\"浙江省\",\"cities\":["
            + "{\"n\":\"杭州市\",\"districts\":[\"上城区\",\"西湖区\",\"滨江区\"]},"
            + "{\"n\":\"宁波市\",\"districts\":[\"海曙区\",\"江北区\"]}]},"
            + "{\"n\":\"北京市\",\"cities\":["
            + "{\"n\":\"北京市\",\"districts\":[\"东城区\",\"朝阳区\",\"海淀区\"]}]}]}";

    private static RegionCatalog catalog() {
        return RegionCatalog.fromJson(THREE_LEVEL);
    }

    @Test
    public void threeLevelPath_restoresAllThreeWheels() {
        int[] indices = RegionPickerDialog.resolveIndices(catalog(),
                "浙江省 杭州市 滨江区".split("\\s+"));
        assertEquals(0, indices[0]); // 浙江省
        assertEquals(0, indices[1]); // 杭州市
        assertEquals(2, indices[2]); // 滨江区, not the first entry
    }

    @Test
    public void municipalityTwoLevelPath_restoresDistrictInsteadOfResettingIt() {
        int[] indices = RegionPickerDialog.resolveIndices(catalog(),
                "北京市 朝阳区".split("\\s+"));
        assertEquals(1, indices[0]); // 北京市 is the second province entry
        assertEquals(0, indices[1]); // its single "city" is 北京市
        assertEquals(1, indices[2]); // 朝阳区 — the regression this test locks down
    }

    @Test
    public void secondCityOfAProvince_keepsItsOwnDistrictSelection() {
        int[] indices = RegionPickerDialog.resolveIndices(catalog(),
                "浙江省 宁波市 江北区".split("\\s+"));
        assertEquals(0, indices[0]);
        assertEquals(1, indices[1]); // 宁波市
        assertEquals(1, indices[2]); // 江北区
    }

    @Test
    public void emptyPath_selectsFirstEntryOfEveryWheel() {
        int[] indices = RegionPickerDialog.resolveIndices(catalog(), new String[0]);
        assertEquals(0, indices[0]);
        assertEquals(0, indices[1]);
        assertEquals(0, indices[2]);
    }

    @Test
    public void unknownNames_doNotThrowAndFallBackToZero() {
        int[] indices = RegionPickerDialog.resolveIndices(catalog(),
                "火星省 火星市 火星区".split("\\s+"));
        assertEquals(0, indices[0]);
        assertEquals(0, indices[1]);
        assertEquals(0, indices[2]);

        int[] partial = RegionPickerDialog.resolveIndices(catalog(), "浙江省".split("\\s+"));
        assertEquals(0, partial[0]);
        assertEquals(0, partial[1]);
        assertEquals(0, partial[2]);
    }
}
