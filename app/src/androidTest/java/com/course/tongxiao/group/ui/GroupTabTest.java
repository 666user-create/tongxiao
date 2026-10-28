package com.course.tongxiao.group.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import com.course.tongxiao.group.TestAwait;
import com.course.tongxiao.group.protocol.GroupProtocol;
import com.course.tongxiao.data.TongxiaoDb;
import com.course.tongxiao.data.entity.GroupMember;
import com.course.tongxiao.data.entity.RemoteAlarm;
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

    @Test
    public void operationLogSection_isNeverABareHeading() throws Exception {
        // Regression: when the log was empty the TextView was GONE and nothing took
        // its place, so 操作记录 stood over blank space while 舍友/待我确认 both had
        // an explicit empty state.
        identity.setGroupCode("654321");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            openGroupTab(scenario);
            Thread.sleep(600);

            View log = awaitView(scenario, R.id.group_op_log, View.class);
            View logEmpty = awaitView(scenario, R.id.group_op_log_empty, View.class);
            assertNotNull("operation log view inflated", log);
            assertNotNull("operation log empty state inflated", logEmpty);

            boolean logVisible = log.getVisibility() == View.VISIBLE;
            boolean emptyVisible = logEmpty.getVisibility() == View.VISIBLE;
            assertTrue("exactly one of {log, empty state} must be visible, got log="
                            + logVisible + " empty=" + emptyVisible,
                    logVisible ^ emptyVisible);
        }
    }

    @Test
    public void masterSwitchOff_showsTheBannerWhenTheTabIsShownAgain() throws Exception {
        // Regression: the banner was only recomputed in onCreateView/onResume, and the
        // tab host switches tabs with show/hide (no onResume), so turning 允许宿舍互助
        // off in 设置 and coming back still claimed roommates could mute you.
        com.course.tongxiao.data.SettingsStore settings =
                new com.course.tongxiao.data.SettingsStore(ctx);
        boolean original = settings.isGroupMasterSwitchOn();
        settings.setGroupMasterSwitchOn(true);
        identity.setGroupCode("654321");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            openGroupTab(scenario);
            assertNotNull("dorm panel rendered",
                    awaitView(scenario, R.id.group_master_off_banner, View.class));
            Thread.sleep(400);
            assertEquals("master switch is on: no banner",
                    View.GONE, visibility(scenario, R.id.group_master_off_banner));

            // The user turns the master switch off on the settings tab ...
            settings.setGroupMasterSwitchOn(false);
            scenario.onActivity(activity -> ((BottomNavigationView)
                    activity.findViewById(R.id.bottom_nav)).setSelectedItemId(R.id.tab_settings));
            Thread.sleep(400);
            // ... and comes back to the dorm tab.
            openGroupTab(scenario);
            Thread.sleep(500);

            assertEquals("the master-off warning must refresh on tab switch",
                    View.VISIBLE, visibility(scenario, R.id.group_master_off_banner));
        } finally {
            settings.setGroupMasterSwitchOn(original);
        }
    }

    private static int visibility(ActivityScenario<MainActivity> scenario, int id) {
        final int[] result = {-1};
        scenario.onActivity(activity -> {
            View view = activity.findViewById(id);
            result[0] = view == null ? -1 : view.getVisibility();
        });
        return result[0];
    }

    @Test
    public void ringingSharedAlarm_showsRingingBadgeAndMuteButton() throws Exception {
        // G4/B2 regression: the demo needs a visible mute entry. Seed the state the
        // peer would have pushed (member + mirror row with ringingNow), then assert
        // the page renders both the badge and the button.
        String peerId = "peer-ringing-device";
        identity.setGroupCode("445566");
        seedMember(peerId, "小李");
        seedMirror(peerId, "uid-ringing", "06:40", true);

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            openGroupTab(scenario);

            assertNotNull("the member row appears", awaitView(scenario, R.id.group_member_list, View.class));
            TestAwait.until("the mute button is rendered", 5_000L,
                    () -> findViewWithText(scenario, text(R.string.group_mute)) != null);
            assertNotNull("the ringing badge is rendered",
                    findViewWithText(scenario, text(R.string.group_member_ringing)));
            assertNotNull("the shared alarm time is rendered",
                    findViewWithText(scenario, "06:40"));
        }
    }

    @Test
    public void sharedAlarmNotRinging_hasNoMuteButton() throws Exception {
        String peerId = "peer-quiet-device";
        identity.setGroupCode("445566");
        seedMember(peerId, "小王");
        seedMirror(peerId, "uid-quiet", "07:10", false);

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            openGroupTab(scenario);
            assertNotNull(awaitView(scenario, R.id.group_member_list, View.class));

            TestAwait.until("the mirrored time is rendered", 5_000L,
                    () -> findViewWithText(scenario, "07:10") != null);
            assertNull("mute must only be offered for a ringing alarm",
                    findViewWithText(scenario, text(R.string.group_mute)));
        }
    }

    private static View findViewWithText(ActivityScenario<MainActivity> scenario, String text) {
        final View[] found = {null};
        scenario.onActivity(activity -> {
            View root = activity.findViewById(R.id.group_member_list);
            found[0] = root == null ? null : findByText(root, text);
        });
        return found[0];
    }

    private static View findByText(View view, String text) {
        if (view instanceof TextView
                && text.contentEquals(((TextView) view).getText())) {
            return view;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View hit = findByText(group.getChildAt(i), text);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private void seedMember(String deviceId, String nick) {
        GroupMember member = new GroupMember();
        member.deviceId = deviceId;
        member.nick = nick;
        member.groupKey = "445566";
        member.grantedMask = GroupMember.PERM_MUTE | GroupMember.PERM_SEE;
        member.online = true;
        member.joinAt = System.currentTimeMillis();
        testDb().groupMemberDao().upsert(member);
    }

    private void seedMirror(String owner, String uid, String timeText, boolean ringing) {
        RemoteAlarm row = new RemoteAlarm();
        row.ownerDeviceId = owner;
        row.srcAlarmUid = uid;
        row.alarmLabel = "早八";
        row.timeText = timeText;
        row.repeatText = "智能工作日";
        row.enabled = true;
        row.ringingNow = ringing;
        testDb().remoteAlarmDao().upsertAll(java.util.Collections.singletonList(row));
    }

    /**
     * Re-joining a group must not undo a deliberate per-alarm opt-out.
     *
     * <p>The first join bulk-shares pre-existing alarms (otherwise nothing is ever
     * pushed); after that, sharedToGroup=false is the user's choice and a second
     * join has to leave it alone.
     */
    @Test
    public void rejoiningAGroup_doesNotOverrideAPerAlarmOptOut() throws Exception {
        com.course.tongxiao.data.entity.Alarm alarm = new com.course.tongxiao.data.entity.Alarm();
        alarm.label = "不共享的闹钟";
        alarm.hour = 6;
        alarm.minute = 40;
        alarm.enabled = true;
        alarm.sharedToGroup = false; // explicit opt-out
        final int id = (int) testDb().alarmDao().insert(alarm);
        identity.setGroupCode(""); // fresh device: backfill has not happened yet

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            openGroupTab(scenario);
            View createButton = awaitView(scenario, R.id.group_create_button, View.class);
            assertNotNull(createButton);

            // First join: the bulk share is expected to run once.
            scenario.onActivity(activity ->
                    activity.findViewById(R.id.group_create_button).performClick());
            Thread.sleep(800);
            assertTrue("first join shares pre-existing alarms",
                    testDb().alarmDao().getById(id).sharedToGroup);

            // User opts this one alarm out again, then leaves and re-joins.
            com.course.tongxiao.data.entity.Alarm row = testDb().alarmDao().getById(id);
            row.sharedToGroup = false;
            testDb().alarmDao().update(row);
            identity.setGroupCode("");

            try (ActivityScenario<MainActivity> second =
                         ActivityScenario.launch(MainActivity.class)) {
                openGroupTab(second);
                assertNotNull(awaitView(second, R.id.group_create_button, View.class));
                second.onActivity(activity ->
                        activity.findViewById(R.id.group_create_button).performClick());
                Thread.sleep(800);

                assertFalse("re-joining must respect the opt-out",
                        testDb().alarmDao().getById(id).sharedToGroup);
            }
        } finally {
            testDb().alarmDao().deleteById(id);
        }
    }
    private TongxiaoDb testDb() {
        return TongxiaoDb.get(ctx);
    }

    private String text(int stringRes) {
        return ctx.getString(stringRes);
    }
}
