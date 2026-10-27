package com.course.tongxiao.group.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.course.tongxiao.MainActivity;
import com.course.tongxiao.R;
import com.course.tongxiao.group.GroupCleanup;
import com.course.tongxiao.group.GroupIdentity;
import com.course.tongxiao.group.PeerStatsStore;
import com.course.tongxiao.group.protocol.GroupProtocol;
import com.course.tongxiao.data.TongxiaoDb;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Step 5.5 G5: the dorm page actually renders inside the tab host and its two
 * states switch correctly (design.md §7 ④).
 *
 * <p>This is what catches a broken layout or a mis-wired tab: the assertions check
 * the inflated view tree, not just that the fragment class exists.
 */
@RunWith(AndroidJUnit4.class)
public class GroupTabTest {

    private Context ctx;
    private GroupIdentity identity;
    private String originalGroupCode;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        identity = new GroupIdentity(ctx);
        originalGroupCode = identity.groupCode();
        identity.setGroupCode(""); // start from "not in a group"
    }

    @After
    public void tearDown() {
        GroupCleanup.removeAllMembers(TongxiaoDb.get(ctx), new PeerStatsStore(ctx));
        identity.setGroupCode(originalGroupCode);
    }

    private static void openGroupTab(ActivityScenario<MainActivity> scenario) {
        scenario.onActivity(activity ->
                ((BottomNavigationView) activity.findViewById(R.id.bottom_nav))
                        .setSelectedItemId(R.id.tab_group));
    }

    private static <T extends View> T awaitView(ActivityScenario<MainActivity> scenario,
                                                int id, Class<T> type) throws Exception {
        for (int i = 0; i < 50; i++) {
            final View[] found = {null};
            scenario.onActivity(activity -> found[0] = activity.findViewById(id));
            if (found[0] != null) {
                return type.cast(found[0]);
            }
            Thread.sleep(100);
        }
        return null;
    }

    @Test
    public void groupTab_withoutAGroup_showsTheJoinCard() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            openGroupTab(scenario);

            View joinCard = awaitView(scenario, R.id.group_join_card, View.class);
            View panel = awaitView(scenario, R.id.group_panel, View.class);
            assertNotNull("dorm page inflated in the tab host", joinCard);
            assertNotNull("in-group panel exists", panel);

            assertEquals("no group yet: the join card is what the user sees",
                    View.VISIBLE, joinCard.getVisibility());
            assertEquals("in-group panel stays hidden", View.GONE, panel.getVisibility());

            EditText codeInput = awaitView(scenario, R.id.group_code_input, EditText.class);
            assertNotNull("group-code input rendered", codeInput);
            assertNotNull("create button rendered",
                    awaitView(scenario, R.id.group_create_button, View.class));
            assertNotNull("join button rendered",
                    awaitView(scenario, R.id.group_join_button, View.class));
        }
    }

    @Test
    public void creatingAGroup_persistsTheCodeAndSwitchesToTheMemberPanel() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            openGroupTab(scenario);
            View createButton = awaitView(scenario, R.id.group_create_button, View.class);
            assertNotNull(createButton);

            scenario.onActivity(activity ->
                    activity.findViewById(R.id.group_create_button).performClick());
            Thread.sleep(600);

            String stored = identity.groupCode();
            assertTrue("a 6-digit group code must be persisted (design.md §4), got '" + stored + "'",
                    GroupProtocol.isValidGroupCode(stored));

            View panel = awaitView(scenario, R.id.group_panel, View.class);
            View joinCard = awaitView(scenario, R.id.group_join_card, View.class);
            assertNotNull(panel);
            assertNotNull(joinCard);
            assertEquals("member panel becomes visible", View.VISIBLE, panel.getVisibility());
            assertEquals("join card is replaced", View.GONE, joinCard.getVisibility());

            TextView codeText = awaitView(scenario, R.id.group_code_text, TextView.class);
            assertNotNull(codeText);
            assertTrue("the group code is shown to read out loud",
                    codeText.getText().toString().contains(stored));

            TextView nickText = awaitView(scenario, R.id.group_nick_text, TextView.class);
            assertNotNull(nickText);
            assertEquals("my nickname is shown next to the code",
                    "我：" + identity.nick(), nickText.getText().toString());
        }
    }
}
