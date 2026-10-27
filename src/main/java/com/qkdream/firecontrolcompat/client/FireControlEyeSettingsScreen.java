package com.qkdream.firecontrolcompat.client;

import com.hooya.stabilizedturret.client.ControllerKeyMappingCompat;
import com.hooya.stabilizedturret.content.controller.RemoteControllerCompat;
import com.qkdream.firecontrolcompat.FireControlLeadSettings;
import com.qkdream.firecontrolcompat.RadarNameState;
import com.qkdream.firecontrolcompat.RadarNameStore;
import com.qkdream.firecontrolcompat.network.LaserCodePayload;
import com.qkdream.firecontrolcompat.network.LeadSettingsPayload;
import com.qkdream.firecontrolcompat.network.RadarNamePayload;
import com.qkdream.firecontrolcompat.network.SonarSelectBindPayload;
import com.qkdream.firecontrolcompat.sonar.SonarSelectInputState;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * This mod's own settings page, opened from the fire control computer's home menu
 * beside the sonar page.
 *
 * <p>These rows used to be injected into the pack's fire-control page. Keeping them
 * there meant living inside a page whose layout, scrolling and scroll bar all belong
 * to somebody else - and every one of those had to be worked around, which is where
 * the gaps, the drifting rows and the short scroll thumb came from. They live here
 * instead: the pack's page is left exactly as its author wrote it, and this one is
 * laid out by a single loop of ours, so a row cannot be out of step with its
 * neighbours.</p>
 */
public class FireControlEyeSettingsScreen extends Screen {

    private static final int PANEL_WIDTH = 330;
    /**
     * Tall enough for the seven rows the page carries, in three titled groups. The rows used to be
     * pitched thirty pixels apart and squeezed to eighteen on a short window, which put a twenty
     * pixel button on an eighteen pixel line - rows overlapped and the sonar rows' text was cut off.
     * One fixed pitch, one fixed button height, and a titled group between the families: the page is
     * the same shape on every window, and no row can sit on its neighbour.
     */
    private static final int PANEL_HEIGHT = 340;
    private static final int CONTENT_LEFT = 18;
    /** Content-space top of the first group. The panel's own header band ends at 29. */
    private static final int CONTENT_TOP = 32;
    /** Row pitch: an eighteen pixel control plus two pixels of air. */
    private static final int STEP = 20;
    /** Control height. Text is nine pixels tall, so it is centred with room to spare. */
    private static final int ROW_HEIGHT = 18;
    /** Height of a group title band. */
    private static final int SECTION_HEIGHT = 10;
    /** Air between two groups. */
    private static final int GROUP_GAP = 3;
    private static final int FIELD_WIDTH = 90;
    /** Width reserved for a row's own label, drawn at the same x as the group titles. */
    private static final int LABEL_WIDTH = 44;

    private static final int OUTLINE = -13357272;
    private static final int BRASS = -4681667;
    private static final int INNER_BORDER = -9017782;
    private static final int BACKGROUND = -858162;
    private static final int HEADER = -9739950;
    private static final int LABEL = -12765137;
    private static final int WHITE = 16777215;
    private static final int DIMMED = -8355712;

    private final Screen parent;
    private final BlockPos controllerPos;
    private final UUID controllerSubLevel;

    private boolean gunLead;
    private boolean verticalLaunch;
    private boolean imageLock;
    /** Cannon family the lead is solved for, and the powder charge of a big cannon shot. */
    private com.qkdream.firecontrolcompat.LeadCannonKind cannonKind =
            com.qkdream.firecontrolcompat.LeadCannonKind.SMALL_CANNON;
    private int bigCharge = 4;
    private net.minecraft.client.gui.components.Button cannonKindWidget;
    /** Set once the family or the charge is touched, so a late answer never overwrites the edit. */
    private boolean leadEdited;
    private boolean launchEdited;
    private boolean nameEdited;
    private String laserCode = "";

    /** Set once the code box is typed in, so an untouched box never clears a stored code. */
    private boolean laserTouched;

    private CompatToggleButton launchButton;
    private EditBox nameBox;
    private EditBox laserBox;
    private CompatToggleButton nameSaveButton;

    /**
     * Which sonar key row is listening for a key press, or null while none is.
     *
     * <p>The two sonar target rows used to be injected into the pack's own binding page as
     * extra input actions. 0.9.4 owns that page's action ids and its per-section tables, so an
     * action of ours either landed under a heading that was not ours or renamed one of the
     * pack's own rows; the injection is gone and these two bindings live here, on this page,
     * with a capture of our own.</p>
     */
    private RemoteControllerCompat.Type sonarCapturing;
    /** 线控·普通 (火控学's {@code Type.NORMAL}). */
    private CompatToggleButton sonarNormalButton;
    /** 线控·高级 (火控学's {@code Type.ADVANCED}). */
    private CompatToggleButton sonarAdvancedButton;
    /** 全键盘线控 (火控学's {@code Type.WIRELESS}, the whole-keyboard channel). */
    private CompatToggleButton sonarWirelessButton;
    /** 无线红石 · 频率 A: the first half of the pair the pick answers to (火控学's First). */
    private CompatToggleButton sonarRedstoneFirstButton;
    /** 无线红石 · 频率 B: the second half of that pair (火控学's Second). */
    private CompatToggleButton sonarRedstoneSecondButton;
    /** Set once the filed sonar keys have been asked for, so a rebuild does not ask again. */
    private boolean sonarRefreshSent;

    private int panelLeft;
    private int panelTop;
    private int panelWidth = PANEL_WIDTH;
    private int panelHeight = PANEL_HEIGHT;
    private int scroll;
    /** Row pitch. Fixed: see {@link #STEP}. Every row of every group uses it. */
    private int step = STEP;

    /** Content-space tops of the three group titles, fixed once per layout. */
    private int sectionWeapons;
    private int sectionCallsign;
    private int sectionSonar;

    /** Content-space tops of the seven control rows, fixed once per layout. */
    private int rowLead;
    private int rowBeam;
    private int rowName;
    private int rowLaser;
    private int rowSonarA;
    private int rowSonarB;

    /**
     * Room the footer row needs below the last content row, and the footer button's own height.
     *
     * <p>These two numbers are what the old layout got wrong: the footer was placed by one formula
     * and the page height by another, and the second one - a {@code Math.max} against the sonar rows
     * - put the footer ten pixels lower than the height calculation believed.
     * {@code eye-rows built … footerIn=false} was that, and the footer was drawn past the panel's
     * bottom border. One constant pair, used by every place that measures the page, is the fix.</p>
     *
     * <p>The room is the hint line <b>plus</b> its two gaps, and it is not a guess: the hint used to
     * be drawn at {@code footerRow - 12} while only four pixels were reserved, so on the reported
     * window it landed <em>inside</em> the sonar row - the bottom line of text sat on the sonar
     * buttons and read as overlapping the done/cancel pair. 面包学's own controller page draws its
     * footer the other way round (the buttons are pinned to {@code viewport.bottom - 32} and the
     * scrollable rows stop above them), which is what this band reproduces: buttons last, hint above
     * them, content above the hint.</p>
     */
    private static final int FOOTER_HINT_GAP = 2;

    /** One nine-pixel text line. */
    private static final int HINT_HEIGHT = 9;

    /** Air between the hint's last text line and the done/cancel buttons. */
    private static final int HINT_FOOTER_GAP = 3;

    private static final int FOOTER_ROOM = FOOTER_HINT_GAP + HINT_HEIGHT + HINT_FOOTER_GAP;

    private static final int FOOTER_HEIGHT = 22;

    /** Set while the page's own scroll bar is being dragged. */
    private boolean barDragging;

    /**
     * Content-space top of the footer row, fixed once per layout by {@link #init()}.
     *
     * <p>Layout, the drawn hint text, the scroll range and the footer's own position all read this
     * one number, so the footer cannot be measured with one formula and drawn with another.</p>
     */
    private int footerRowTop;

    /**
     * The call sign as typed on this page, kept across the layout being rebuilt.
     *
     * <p>Scrolling rebuilds the page's widgets - that is how a scrolled row is moved - and a rebuilt
     * {@code EditBox} is a new box with the value it was built with. Reading the file again on every
     * rebuild would therefore throw away a name that had been typed but not yet saved, three wheel
     * notches into typing it.</p>
     */
    private String pendingName;

    public FireControlEyeSettingsScreen(Screen parent, BlockPos controllerPos, UUID controllerSubLevel) {
        super(Component.translatable("gui.firecontrolcompat.eye_settings"));
        this.parent = parent;
        this.controllerPos = controllerPos;
        this.controllerSubLevel = controllerSubLevel;
        this.gunLead = FireControlLeadSettings.gunLeadEnabled();
        this.verticalLaunch = FireControlLeadSettings.beamVerticalLaunch();
        this.imageLock = FireControlLeadSettings.imageLockEnabled();
        this.cannonKind = FireControlLeadSettings.leadCannonKind();
        this.bigCharge = FireControlLeadSettings.bigCannonCharge();
    }

    @Override
    protected void init() {
        // Logged first, before anything on this page can throw: the line below is the
        // one that says whether this page was built at all. It used to sit after the
        // row layout, so a page that failed half way through left no trace and looked
        // exactly like a page nobody had opened.
        com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                "[firecontrolcompat] eye-page init entered pos={} sub={} window={}x{} scroll={}",
                this.controllerPos, this.controllerSubLevel, this.width, this.height, this.scroll);
        this.panelWidth = Math.min(PANEL_WIDTH, Math.max(160, this.width - 20));
        this.panelHeight = Math.min(PANEL_HEIGHT, Math.max(120, this.height - 20));
        this.panelLeft = Math.max(0, (this.width - this.panelWidth) / 2);
        this.panelTop = Math.max(2, (this.height - this.panelHeight) / 2);
        this.step = STEP;

        // ---- layout, in content coordinates, before a single widget exists -------------------
        //
        // Three titled groups instead of one long ladder: 武器与提前量 (what the sight solves),
        // 呼叫与编码 (the two fields that identify this computer) and 声呐绑定 (the four channels the
        // sonar pick answers to). The gap and the title are what stop the four sonar buttons from
        // reading as a fifth row of the lead settings.
        int y = CONTENT_TOP;
        this.sectionWeapons = y;
        y += SECTION_HEIGHT;
        this.rowLead = y;
        y += this.step;
        this.rowBeam = y;
        y += this.step;
        y += GROUP_GAP;
        this.sectionCallsign = y;
        y += SECTION_HEIGHT;
        this.rowName = y;
        y += this.step;
        this.rowLaser = y;
        y += this.step;
        y += GROUP_GAP;
        this.sectionSonar = y;
        y += SECTION_HEIGHT;
        this.rowSonarA = y;
        y += this.step;
        this.rowSonarB = y;
        // The footer starts under the last row's own height, not under the next row's pitch: the
        // two pixels of air between rows belong to the rows, and the footer band is measured from
        // the bottom edge of the last control (see FOOTER_ROOM).
        this.footerRowTop = y + ROW_HEIGHT + FOOTER_ROOM;

        // The scroll position is kept across a rebuild - scrolling IS a rebuild - and clamped
        // against the page measured just now. It used to be zeroed here, which is why the wheel and
        // the bar drew a page that never moved: every notch scrolled and then threw the scroll away
        // one statement later.
        this.scroll = Math.max(0, Math.min(this.scroll, this.maxScroll()));

        int left = this.panelLeft + CONTENT_LEFT;

        // The two lead values belong to the computer this page was opened from, so they are
        // taken from the answer that computer sent when it was opened - not from the mod's
        // global defaults, which are only the fallback while a computer has never been set.
        this.compat$adoptControllerLead();
        com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                "[firecontrolcompat] lead-open pos={} sub={} kind={} charge={} panelTop={} panelHeight={} footerTop={}",
                this.controllerPos, this.controllerSubLevel, this.cannonKind, this.bigCharge,
                this.panelTop, this.panelHeight, this.compat$footerTop());

        // The lead row carries three controls: the on/off switch kept short, then which cannon
        // family the sight solves for, then the powder charge a big cannon shot is given. All three
        // sit on the same row line, so they scroll with the page like everything else here.
        // The powder charge only means something to a big cannon, so that button is only built
        // while the family is the big cannon; the value itself is kept either way.
        int leadRowY = this.cy(this.rowLead);
        int leadTextW = 84;
        int kindW = 116;
        int chargeW = 74;
        int rightEdge = this.panelLeft + this.panelWidth - 12;
        this.addRenderableWidget(new CompatToggleButton(
                left, leadRowY, leadTextW, ROW_HEIGHT,
                Component.literal("提前量：" + (this.gunLead ? "开" : "关")),
                button -> {
                    this.gunLead = !this.gunLead;
                    button.setMessage(Component.literal("提前量：" + (this.gunLead ? "开" : "关")));
                }));
        this.cannonKindWidget = this.addRenderableWidget(new CompatToggleButton(
                left + leadTextW + 8, leadRowY, kindW, ROW_HEIGHT, this.compat$cannonKindText(),
                button -> {
                    this.cannonKind = this.compat$nextCannonKind(this.cannonKind);
                    this.leadEdited = true;
                    // Kept at once: this is the value that used to be lost when the page was
                    // left with escape, or when the done key was pushed off the bottom edge.
                    this.compat$saveLead("cannon-kind");
                    // The charge button appears on the big cannon row and is gone on the others,
                    // so the row is laid out again instead of leaving a stale key behind.
                    this.rebuildWidgets();
                }));
        if (this.cannonKind == com.qkdream.firecontrolcompat.LeadCannonKind.BIG_CANNON) {
            this.addRenderableWidget(new CompatToggleButton(
                    rightEdge - chargeW, leadRowY, chargeW, ROW_HEIGHT,
                    Component.literal("装药" + this.bigCharge),
                    button -> {
                        this.bigCharge = this.bigCharge >= 8 ? 1 : this.bigCharge + 1;
                        this.leadEdited = true;
                        this.compat$saveLead("cannon-charge");
                        button.setMessage(Component.literal("装药" + this.bigCharge));
                    }));
        }

        // 架束弹模式 and 红外锁定 share one line: neither label is long, and side by side they leave
        // the second line of the group free instead of making the page two rows taller.
        int halfWidth = Math.max(70, (this.panelWidth - CONTENT_LEFT * 2 - 6) / 2);
        int beamRowY = this.cy(this.rowBeam);
        this.launchButton = this.compat$toggle(
                left, beamRowY, halfWidth, on -> this.compat$launchText(on),
                this.verticalLaunch, on -> {
                    this.verticalLaunch = on;
                    this.launchEdited = true;
                    // The mode is filed per computer: the beam-riding round reads it from the
                    // rig that fired it, so two mounts can launch differently.
                    if (this.controllerPos != null) {
                        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                                new com.qkdream.firecontrolcompat.network.BeamLaunchModePayload(
                                        this.controllerPos, this.controllerSubLevel, on));
                    }
                });
        this.addRenderableWidget(this.launchButton);

        this.addRenderableWidget(this.compat$toggle(
                left + halfWidth + 6, beamRowY, halfWidth, "gui.firecontrolcompat.ir_image_lock",
                this.imageLock, on -> this.imageLock = on));

        // The call sign shares its row with its label and the save button; the laser code shares its
        // row with its own label. Both labels are drawn at the row's own line (see render), four
        // characters wide, so the fields can start at a constant offset and every row lines up.
        int nameRowY = this.cy(this.rowName);
        int nameBoxX = left + LABEL_WIDTH;
        int saveWidth = 56;
        int nameBoxWidth = Math.max(60,
                this.panelWidth - CONTENT_LEFT * 2 - LABEL_WIDTH - saveWidth - 6);
        this.nameBox = new EditBox(this.font, nameBoxX, nameRowY,
                nameBoxWidth, ROW_HEIGHT, Component.literal(""));
        this.nameBox.setMaxLength(RadarNameStore.MAX_NAME_LENGTH);
        this.nameBox.setHint(Component.literal("最多4字符"));
        this.nameBox.setValue(this.compat$nameValue());
        this.nameBox.setResponder(value -> {
            this.pendingName = value;
            if (this.nameBox != null && this.nameBox.isFocused()) {
                this.nameEdited = true;
                this.compat$resetNameButton();
            }
        });
        this.addRenderableWidget(this.nameBox);
        this.nameSaveButton = new CompatToggleButton(
                nameBoxX + nameBoxWidth + 6, nameRowY, saveWidth, ROW_HEIGHT,
                Component.translatable("gui.firecontrolcompat.save_name"),
                button -> this.compat$saveName());
        this.addRenderableWidget(this.nameSaveButton);

        this.laserBox = new EditBox(this.font, nameBoxX, this.cy(this.rowLaser), FIELD_WIDTH, ROW_HEIGHT,
                Component.translatable("gui.firecontrolcompat.laser_code"));
        this.laserBox.setMaxLength(4);
        this.laserBox.setHint(Component.translatable("gui.firecontrolcompat.laser_code_hint"));
        this.laserBox.setValue(this.laserCode);
        this.laserBox.setResponder(value -> {
            if (!value.isEmpty() && !value.chars().allMatch(Character::isDigit)) {
                this.laserBox.setValue(value.replaceAll("[^0-9]", ""));
                return;
            }
            this.laserCode = value;
            this.laserTouched = true;
        });
        this.addRenderableWidget(this.laserBox);

        // The sonar pick is ONE action - "cycle the sonar target" - and it answers to all four of
        // the exchange channels the pack itself offers: the wireless redstone frequency pair, the
        // ordinary drive-by-wire controller, the advanced one, and the full-keyboard wireless
        // controller. The two rows this page used to carry (声呐目标 A / 声呐目标 B) were the same
        // action twice - both ended in the same SonarLock.select, which already steps to the next
        // contact on every press - which is why a crew read two rows and asked why there were two
        // keys for one job. They are one group now, two per line: three buttons on one line left
        // each of them ninety pixels wide and the longest of them clipped.
        //
        // All of these widgets are laid out in content coordinates first and added last, after
        // every other row on the page: a widget added later draws on top of the ones added before
        // it, so adding them last is what makes sure nothing on this page can ever cover them.
        int sonarHalf = Math.max(90, (this.panelWidth - CONTENT_LEFT * 2 - 6) / 2);
        int sonarThird = Math.max(60, (this.panelWidth - CONTENT_LEFT * 2 - 12) / 3);
        int sonarLine1 = this.cy(this.rowSonarA);
        int sonarLine2 = this.cy(this.rowSonarB);
        // Pinned, not scrolled: the done and cancel keys stay inside the panel on every window.
        int footerTop = this.panelTop + this.compat$pinnedFooterRow();
        int panelBottom = this.panelTop + this.panelHeight - 7;
        this.sonarNormalButton = this.addRenderableWidget(new CompatToggleButton(
                left, sonarLine1, sonarThird, ROW_HEIGHT, this.compat$sonarText(RemoteControllerCompat.Type.NORMAL),
                button -> this.compat$captureSonarKey(RemoteControllerCompat.Type.NORMAL)));
        this.sonarAdvancedButton = this.addRenderableWidget(new CompatToggleButton(
                left + sonarThird + 6, sonarLine1, sonarThird, ROW_HEIGHT,
                this.compat$sonarText(RemoteControllerCompat.Type.ADVANCED),
                button -> this.compat$captureSonarKey(RemoteControllerCompat.Type.ADVANCED)));
        this.sonarWirelessButton = this.addRenderableWidget(new CompatToggleButton(
                left + (sonarThird + 6) * 2, sonarLine1, sonarThird, ROW_HEIGHT,
                this.compat$sonarText(RemoteControllerCompat.Type.WIRELESS),
                button -> this.compat$captureSonarKey(RemoteControllerCompat.Type.WIRELESS)));
        // The wireless redstone row is 火控学's own shape, not a rule of ours: a receiver answers to
        // a pair of frequencies, the operator files them one half at a time from the item in hand,
        // and an empty hand clears that half. Two buttons, A and B - the pack's own input page has
        // exactly these two frequency slots per action, printed as "频率 A: <item>" / "频率 B: …".
        this.sonarRedstoneFirstButton = this.addRenderableWidget(new CompatToggleButton(
                left, sonarLine2, sonarHalf, ROW_HEIGHT, this.compat$sonarFrequencyText(true),
                button -> this.compat$bindSonarRedstone(true)));
        this.sonarRedstoneSecondButton = this.addRenderableWidget(new CompatToggleButton(
                left + sonarHalf + 6, sonarLine2, sonarHalf, ROW_HEIGHT, this.compat$sonarFrequencyText(false),
                button -> this.compat$bindSonarRedstone(false)));
        this.addRenderableWidget(new CompatToggleButton(
                left, footerTop, 140, 20,
                Component.translatable("gui.done"),
                button -> this.compat$saveAndClose()));
        this.addRenderableWidget(new CompatToggleButton(
                this.panelLeft + this.panelWidth - CONTENT_LEFT - 140, footerTop, 140, 20,
                Component.translatable("gui.cancel"),
                button -> this.compat$close()));
        this.compat$refreshSonarKeys();
        int hintTop = this.panelTop + this.compat$pinnedFooterRow() - HINT_FOOTER_GAP - this.font.lineHeight;
        com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                "[firecontrolcompat] eye-rows built sections={},{},{} rows={},{},{},{},{},{}"
                        + " sonarLine1={} sonarLine2={} footer={} hint={} hintTop={} hintBottom={}"
                        + " sonarBottom={} panelTop={} panelHeight={} scroll={}"
                        + " step={} contentHeight={} visible={} maxScroll={}"
                        + " line1in={} line2in={} footerIn={} hintClear={} bar={}",
                this.sectionWeapons, this.sectionCallsign, this.sectionSonar,
                this.rowLead, this.rowBeam, this.rowName, this.rowLaser, this.rowSonarA, this.rowSonarB,
                sonarLine1, sonarLine2, footerTop,
                this.compat$pinnedFooterRow(), hintTop, hintTop + HINT_HEIGHT,
                Math.max(sonarLine1, sonarLine2) + ROW_HEIGHT,
                this.panelTop, this.panelHeight, this.scroll, this.step, this.contentHeight(),
                this.visibleContentBottom(), this.maxScroll(),
                sonarLine1 >= this.panelTop + 29 && sonarLine1 + ROW_HEIGHT <= panelBottom,
                sonarLine2 >= this.panelTop + 29 && sonarLine2 + ROW_HEIGHT <= panelBottom,
                footerTop >= this.panelTop + 29 && footerTop + 20 <= panelBottom,
                // The one question the reported page answered "no" to: the hint's own band must sit
                // between the last sonar row and the done/cancel keys, overlapping neither.
                hintTop >= Math.max(sonarLine1, sonarLine2) + ROW_HEIGHT
                        && hintTop + HINT_HEIGHT <= footerTop,
                this.maxScroll() > 0);
        if (this.controllerPos != null && !this.sonarRefreshSent) {
            // Ask the server what this computer already answers to, so the rows come up with the
            // filed keys instead of empty ones: the file lives on the server, and the page only
            // ever draws what it was told. Sent once - the layout is rebuilt on every wheel notch,
            // and a refresh request per notch would be a packet per notch.
            this.sonarRefreshSent = true;
            PacketDistributor.sendToServer(new SonarSelectBindPayload(
                    this.controllerPos, this.controllerSubLevel, SonarSelectBindPayload.SLOT_REFRESH, ""));
        }
    }

    // ------------------------------------------------------------------ saving

    /**
     * Files the lead pair with the computer at once, without waiting for the done key.
     *
     * <p>The two controls of the lead row save themselves as they are clicked. A settings page
     * whose only way to keep a value is its done key loses the value to an escape press, and a
     * done key that a small window pushes off the bottom edge loses it just as quietly - both
     * were seen in the field, and both look exactly like "the setting does not stay".</p>
     */
    private void compat$saveLead(String reason) {
        if (this.controllerPos == null) {
            FireControlLeadSettings.applyLeadTarget(this.cannonKind, this.bigCharge);
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] lead-save client source=global-fallback (no controller) reason={} kind={} charge={}",
                    reason, this.cannonKind, this.bigCharge);
            return;
        }
        com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                "[firecontrolcompat] lead-save client send reason={} pos={} sub={} kind={} charge={}",
                reason, this.controllerPos, this.controllerSubLevel, this.cannonKind, this.bigCharge);
        PacketDistributor.sendToServer(new com.qkdream.firecontrolcompat.network.ControllerLeadPayload(
                this.controllerPos, this.controllerSubLevel, this.cannonKind.name(), this.bigCharge));
    }
    private void compat$saveAndClose() {
        // The proximity row is gone from this page. The payload still carries the field,
        // so it is pinned to its legacy default and the wire shape does not change.
        double range = FireControlLeadSettings.beamProximityRange();
        // The torpedo values belong to the sonar page, so the stored ones are carried
        // through untouched here.
        if (this.controllerPos == null) {
            // No computer to file them under: this page was opened on its own, so the
            // mod-level default is all there is. It is written only when there is no
            // computer at all, never as a substitute for one.
            FireControlLeadSettings.applyLeadTarget(this.cannonKind, this.bigCharge);
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] lead-save client source=global-fallback (no controller) kind={} charge={}",
                    this.cannonKind, this.bigCharge);
        } else {
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] lead-save client send pos={} sub={} kind={} charge={}",
                    this.controllerPos, this.controllerSubLevel, this.cannonKind, this.bigCharge);
        }
        FireControlLeadSettings.apply(
                this.gunLead, this.verticalLaunch, range, false, 0.0D, this.imageLock,
                FireControlLeadSettings.torpedoHoldDepth(),
                FireControlLeadSettings.torpedoDepth(),
                FireControlLeadSettings.torpedoActivationDistance());
        PacketDistributor.sendToServer(new LeadSettingsPayload(
                this.gunLead, this.verticalLaunch, range, false, 0.0D, this.imageLock,
                FireControlLeadSettings.torpedoHoldDepth(),
                FireControlLeadSettings.torpedoDepth(),
                FireControlLeadSettings.torpedoActivationDistance()));
        if (this.controllerPos != null) {
            // The family and the charge are written onto the computer this page was opened
            // from, so two computers keep two different guns in sight. This repeats what the
            // two lead controls already sent on their own click; it is here so a page left
            // untouched, or one whose clicks arrived before the server knew the computer, is
            // still filed by the done key.
            this.compat$saveLead("done");
        }
        if (this.controllerPos != null) {
            PacketDistributor.sendToServer(new RadarNamePayload(
                    this.controllerPos, this.controllerSubLevel,
                    this.nameBox == null ? "" : this.nameBox.getValue()));
            if (!this.laserTouched) {
                // Nothing typed: the computer keeps whatever code it already had.
                this.compat$close();
                return;
            }
            int code = 0;
            String text = this.laserBox == null ? this.laserCode : this.laserBox.getValue().trim();
            if (text != null && !text.isEmpty()) {
                try {
                    code = Math.max(0, Math.min(9999, Integer.parseInt(text)));
                } catch (NumberFormatException ignored) {
                    code = 0;
                }
            }
            PacketDistributor.sendToServer(new LaserCodePayload(
                    this.controllerPos, this.controllerSubLevel, code));
        }
        this.compat$close();
    }

    /** Sends the call sign at once, without leaving the page. */
    private void compat$saveName() {
        String name = this.nameBox == null ? "" : this.nameBox.getValue().trim();
        this.pendingName = name;
        if (this.controllerPos != null) {
            PacketDistributor.sendToServer(
                    new RadarNamePayload(this.controllerPos, this.controllerSubLevel, name));
        }
        RadarNameState.applyScreenName(name);
        if (this.nameBox != null && !name.equals(this.nameBox.getValue())) {
            this.nameBox.setValue(name);
        }
        this.nameEdited = false;
        if (this.nameSaveButton != null) {
            this.nameSaveButton.setMessage(Component.translatable("gui.firecontrolcompat.save_name_done"));
            this.nameSaveButton.setTone(CompatToggleButton.Tone.GREEN);
        }
    }

    private void compat$resetNameButton() {
        if (this.nameSaveButton == null) {
            return;
        }
        this.nameSaveButton.setMessage(Component.translatable("gui.firecontrolcompat.save_name"));
        this.nameSaveButton.setTone(CompatToggleButton.Tone.NORMAL);
    }

    private void compat$close() {
        com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                "[firecontrolcompat] lead-close pos={} kind={} charge={} edited={}",
                this.controllerPos, this.cannonKind, this.bigCharge, this.leadEdited);
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(this.parent);
    }

    @Override
    public void onClose() {
        // Leaving with escape keeps what was typed, without sending a save.
        this.compat$close();
    }

    // ------------------------------------------------------------------ layout

    /**
     * A row toggle. The label is built from its state rather than assembled by adding a
     * word on the end: the pack's own wording is {@code "…: %s"}, so the on/off word has
     * to be handed to the translation as its argument - appending it left a literal
     * {@code %s} on screen next to a second on/off word.
     */
    private CompatToggleButton compat$toggle(
            int x, int y, String key, boolean initial, java.util.function.Consumer<Boolean> sink) {
        return this.compat$toggle(x, y, this.compat$fullWidth(), key, initial, sink);
    }

    private CompatToggleButton compat$toggle(int x, int y, int width, String key,
            boolean initial, java.util.function.Consumer<Boolean> sink) {
        return this.compat$toggle(x, y, width,
                on -> Component.translatable(key, Component.translatable(on ? "options.on" : "options.off")),
                initial, sink);
    }

    private CompatToggleButton compat$toggle(
            int x, int y, java.util.function.Function<Boolean, Component> label,
            boolean initial, java.util.function.Consumer<Boolean> sink) {
        return this.compat$toggle(x, y, this.compat$fullWidth(), label, initial, sink);
    }

    private CompatToggleButton compat$toggle(int x, int y, int width,
            java.util.function.Function<Boolean, Component> label,
            boolean initial, java.util.function.Consumer<Boolean> sink) {
        boolean[] state = {initial};
        CompatToggleButton button = new CompatToggleButton(
                x, y, width, ROW_HEIGHT,
                label.apply(initial),
                pressed -> {
                    state[0] = !state[0];
                    sink.accept(state[0]);
                    pressed.setMessage(label.apply(state[0]));
                    ((CompatToggleButton) pressed)
                            .setTone(state[0] ? CompatToggleButton.Tone.GREEN : CompatToggleButton.Tone.NORMAL);
                });
        button.setTone(initial ? CompatToggleButton.Tone.GREEN : CompatToggleButton.Tone.NORMAL);
        return button;
    }

    /** Width of a control that owns a whole line. */
    private int compat$fullWidth() {
        return this.panelWidth - CONTENT_LEFT * 2;
    }

    /** The launch mode row shows the mode itself; it is a choice, not an on/off switch. */
    private Component compat$launchText(boolean vertical) {
        return Component.translatable(
                "gui.firecontrolcompat.beam_launch_mode",
                Component.translatable(vertical
                        ? "gui.firecontrolcompat.vertical_launch"
                        : "gui.firecontrolcompat.horizontal_launch"));
    }

    private int compat$footerTop() {
        // Eight rows below the first one, plus the three group titles and the two gaps between
        // them: the footer sits under the sonar group, and every place that measures the page reads
        // the layout's own number rather than re-deriving it.
        return this.footerRowTop > 0
                ? this.footerRowTop : CONTENT_TOP + FOOTER_ROOM;
    }

    private int contentHeight() {
        return this.compat$footerRowTop() + FOOTER_HEIGHT;
    }

    /** The footer's content top actually used this layout, falling back before {@code init} ran. */
    private int compat$footerRowTop() {
        return this.footerRowTop > 0 ? this.footerRowTop : this.compat$footerTop();
    }

    /**
     * The lowest row top the footer may take without leaving the panel.
     *
     * <p>In content space, like {@link #cy(int)}: {@code panelTop + row} is the screen y. The two
     * pixels are clearance from the panel's own bottom border.</p>
     */
    private int compat$footerRowLimit() {
        return Math.max(CONTENT_TOP + 29, this.panelHeight - 7 - FOOTER_HEIGHT - 2);
    }

    /**
     * Where the footer is actually placed: it follows the page while the page fits, and stops at
     * the panel's bottom edge once the page would push it out.
     *
     * <p>{@code eye-rows built … footerIn=false} was the report this fixes: the done and cancel
     * buttons were laid out from the same scrolled flow as the rows, so on a window short enough to
     * need the scroll bar they sat past the border - the two keys that commit the page were the two
     * keys off the screen. Clamping only the <em>placement</em> keeps the scroll range unchanged
     * (every row can still be reached) while the footer stays where a hand can find it.</p>
     */
    private int compat$pinnedFooterRow() {
        int flow = this.compat$footerRowTop() - this.scroll;
        return Math.max(CONTENT_TOP + 29, Math.min(flow, this.compat$footerRowLimit()));
    }

    /**
     * Last content pixel the panel's scrollable rows may use.
     *
     * <p>{@code cy(top)} puts a row at {@code panelTop + top}, and the scissor in {@code render}
     * stops at {@code panelTop + panelHeight - 7} (the panel's own bottom border). Measuring the
     * scroll range against {@code panelHeight} alone left the last seven pixels of the page
     * unreachable - which is exactly the footer row.</p>
     *
     * <p>The footer's own band is reserved here as well, and there are two of them:</p>
     *
     * <ul>
     *   <li>the buttons alone ({@code -4}): enough while the whole page fits, because then nothing
     *       scrolls and nothing can be dragged under the hint;</li>
     *   <li>buttons <b>and</b> the hint line ({@code -FOOTER_ROOM}): used as soon as the page has to
     *       scroll, so a row scrolled to the bottom stops above the hint instead of running through
     *       it.</li>
     * </ul>
     *
     * <p>The choice is made from the page's own height rather than from the scroll position, so the
     * range is stable: a page that fits is never given a scroll bar by the band it does not use.</p>
     */
    private int visibleContentBottom() {
        int buttonsOnly = Math.max(80, this.panelHeight - 7 - FOOTER_HEIGHT - 4);
        if (this.contentHeight() <= buttonsOnly) {
            return buttonsOnly;
        }
        return Math.max(80, this.panelHeight - 7 - FOOTER_HEIGHT - FOOTER_ROOM);
    }

    private int cy(int top) {
        return this.panelTop + top - this.scroll;
    }

    private int maxScroll() {
        return Math.max(0, this.contentHeight() - this.visibleContentBottom());
    }

    // ------------------------------------------------------------------ input

    /**
     * The wheel.
     *
     * <p>This page is the active screen while it is open, so nothing above it can eat the event:
     * the pack's controller page is its parent, not its container, and a parent screen does not see
     * a child's input. What did eat the scroll was this page itself - {@code rebuildWidgets()} runs
     * {@link #init()}, and {@code init} used to reset {@code scroll} to zero, so a notch moved the
     * page and then put it back before the frame was drawn. That reset is gone; the position is
     * carried through the rebuild and clamped against the new layout.</p>
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = this.maxScroll();
        if (max > 0) {
            int from = this.scroll;
            int next = (int) Math.max(0, Math.min(max, this.scroll - scrollY * this.step));
            if (next != from) {
                this.scroll = next;
                this.rebuildWidgets();
            }
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] scrollbar: kind=wheel from={} to={} max={} bar={}",
                    from, this.scroll, max, this.compat$barThumb());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** Where the scroll bar is drawn: {trackX, trackTop, trackWidth, trackHeight}. */
    private int[] compat$barTrack() {
        int right = this.panelLeft + this.panelWidth;
        int top = this.panelTop + 30;
        int height = this.panelHeight - 42;
        return new int[] { right - 12, top, 6, height };
    }

    /**
     * Height of the draggable thumb, never shorter than a grabbable stub.
     *
     * <p>The ratio is the <em>visible</em> window over the whole page, which is what a scroll bar
     * means; the old one divided the panel's height by the content's, which made the thumb taller
     * than its own track on every window that needed it - a thumb that cannot be dragged anywhere
     * because there is no travel left in the track.</p>
     */
    private int compat$barThumb() {
        int[] track = this.compat$barTrack();
        int visible = this.visibleContentBottom();
        int thumb = (int) ((long) track[3] * visible / Math.max(1, this.contentHeight()));
        return Math.max(16, Math.min(track[3], thumb));
    }

    /** Scroll value for a thumb whose top edge is at this screen y. */
    private int compat$scrollForThumbY(int thumbY) {
        int[] track = this.compat$barTrack();
        int thumb = this.compat$barThumb();
        int travel = Math.max(1, track[3] - thumb);
        double fraction = Math.max(0.0D, Math.min(1.0D, (thumbY - track[1]) / (double) travel));
        return (int) Math.round(fraction * this.maxScroll());
    }

    /**
     * A scroll bar of this page's own, dragged like the one on any other list.
     *
     * <p>The page used to scroll on the wheel alone and only <em>draw</em> a bar, so on a short
     * window the rows below the fold could only be reached by rolling, and the bar looked like a
     * control that did nothing. This one can be grabbed anywhere on its track, dragged, and paged
     * with the wheel.</p>
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && this.maxScroll() > 0) {
            int[] track = this.compat$barTrack();
            boolean onTrack = mouseX >= track[0] - 3 && mouseX <= track[0] + track[2] + 3
                    && mouseY >= track[1] && mouseY <= track[1] + track[3];
            if (onTrack) {
                int from = this.scroll;
                this.barDragging = true;
                int thumb = this.compat$barThumb();
                // Grabbing the thumb keeps the grab point; a click on the bare track jumps there.
                int thumbY = this.compat$thumbY();
                boolean onThumb = mouseY >= thumbY && mouseY <= thumbY + thumb;
                if (!onThumb) {
                    this.scroll = this.compat$scrollForThumbY((int) mouseY - thumb / 2);
                    this.rebuildWidgets();
                }
                com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                        "[firecontrolcompat] scrollbar: kind={} from={} to={} max={}",
                        onThumb ? "thumb" : "track", from, this.scroll, this.maxScroll());
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.barDragging && button == 0) {
            int from = this.scroll;
            this.scroll = this.compat$scrollForThumbY((int) mouseY - this.compat$barThumb() / 2);
            this.rebuildWidgets();
            if (from != this.scroll) {
                com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                        "[firecontrolcompat] scrollbar: kind=thumb from={} to={} max={}",
                        from, this.scroll, this.maxScroll());
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && this.barDragging) {
            this.barDragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** Top edge of the thumb, in screen coordinates. */
    private int compat$thumbY() {
        int[] track = this.compat$barTrack();
        int thumb = this.compat$barThumb();
        int travel = Math.max(1, track[3] - thumb);
        int max = this.maxScroll();
        return track[1] + (int) ((long) travel * this.scroll / Math.max(1, max));
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Left empty on purpose, the way the pack leaves it: the vanilla pass blurs the world
     * and lays a dark tint over everything already drawn. This page draws its own panel
     * and then hands the widgets to {@code super.render}, so that tint landed <em>on the
     * panel and its labels</em> - the page came out dim, and text drawn before the widgets
     * looked smeared while the buttons, drawn after, stayed crisp.
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The computer's own answer may still be in flight when this page comes up, so it is
        // taken in before anything is drawn rather than in the layout alone.
        this.compat$syncLead();
        this.compat$syncLaunchMode();
        // The two sonar rows draw whatever the computer last reported, which can land after the
        // page was built.
        this.compat$refreshSonarKeys();
        // The world behind is dimmed here instead: once, before anything of ours is drawn.
        graphics.fill(0, 0, this.width, this.height, 1744830464);
        int right = this.panelLeft + this.panelWidth;
        int bottom = this.panelTop + this.panelHeight;
        graphics.fill(this.panelLeft + 4, this.panelTop + 5, right + 5, bottom + 6, -1879048192);
        graphics.fill(this.panelLeft, this.panelTop, right, bottom, OUTLINE);
        graphics.fill(this.panelLeft + 2, this.panelTop + 2, right - 2, bottom - 2, BRASS);
        graphics.fill(this.panelLeft + 5, this.panelTop + 5, right - 5, bottom - 5, INNER_BORDER);
        graphics.fill(this.panelLeft + 7, this.panelTop + 28, right - 7, bottom - 7, BACKGROUND);
        graphics.fill(this.panelLeft + 7, this.panelTop + 7, right - 7, this.panelTop + 27, HEADER);
        graphics.fill(this.panelLeft + 8, this.panelTop + 27, right - 8, this.panelTop + 29, -7048138);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.panelTop + 12, WHITE);

        graphics.enableScissor(this.panelLeft + 7, this.panelTop + 29, right - 7, bottom - 7);
        int left = this.panelLeft + CONTENT_LEFT;
        // The three group titles, then the two labels that sit on a field's own line. All of them
        // move with the scroll because they are read through cy().
        this.compat$section(graphics, left, this.sectionWeapons, "gui.firecontrolcompat.section_weapons");
        this.compat$section(graphics, left, this.sectionCallsign, "gui.firecontrolcompat.section_callsign");
        this.compat$section(graphics, left, this.sectionSonar, "gui.firecontrolcompat.section_sonar");
        graphics.drawString(this.font, Component.translatable("gui.firecontrolcompat.radar_name"),
                left, this.cy(this.rowName) + 5, LABEL, false);
        graphics.drawString(this.font, Component.translatable("gui.firecontrolcompat.laser_code"),
                left, this.cy(this.rowLaser) + 5, LABEL, false);
        super.render(graphics, mouseX, mouseY, partialTick);
        // The hint is drawn <b>after</b> the widgets, and at the pinned footer's own line rather
        // than at the scrolled content position: the footer is pinned, so a hint that scrolled with
        // the page would part company with the buttons it describes - and, on the window this was
        // reported on, it was drawn twelve pixels above the buttons while only four were reserved,
        // which put the line of text on top of the sonar row. The band it now occupies is
        // FOOTER_ROOM tall and sits between the last row and the buttons.
        graphics.drawString(this.font, Component.translatable("gui.firecontrolcompat.eye_settings_hint"),
                left, this.panelTop + this.compat$pinnedFooterRow() - HINT_FOOTER_GAP - this.font.lineHeight,
                DIMMED, false);
        graphics.disableScissor();
        this.compat$renderScrollbar(graphics, right, bottom);
        this.compat$syncNameBox();
    }

    /** One group title: the pack's brass colour over a hairline, so a group reads as a block. */
    private void compat$section(GuiGraphics graphics, int left, int contentTop, String key) {
        int y = this.cy(contentTop);
        if (contentTop <= 0) {
            return;
        }
        graphics.drawString(this.font, Component.translatable(key), left, y, LABEL, false);
        int lineX = left + this.font.width(Component.translatable(key)) + 6;
        int lineY = y + 4;
        if (lineX < this.panelLeft + this.panelWidth - CONTENT_LEFT) {
            graphics.fill(lineX, lineY, this.panelLeft + this.panelWidth - CONTENT_LEFT, lineY + 1, INNER_BORDER);
        }
    }

    /** The call sign currently in the box: what was typed here, else what the computer reported. */
    private String compat$nameValue() {
        if (this.pendingName != null) {
            return this.pendingName;
        }
        return RadarNameState.screenName();
    }

    private void compat$renderScrollbar(GuiGraphics graphics, int right, int bottom) {
        if (this.maxScroll() <= 0) {
            return;
        }
        int[] track = this.compat$barTrack();
        int thumb = this.compat$barThumb();
        int thumbY = this.compat$thumbY();
        // A wider, darker track than the pack's own, so it reads as something to grab: this page
        // carries its own scroll bar instead of relying on the fire control screen's, whose moving
        // lost a widget list along the way and cannot be trusted to carry ours.
        graphics.fill(track[0] - 1, track[1] - 1, track[0] + track[2] + 1, track[1] + track[3] + 1, -13357272);
        graphics.fill(track[0], track[1], track[0] + track[2], track[1] + track[3], -14342875);
        int thumbColor = this.barDragging ? -1912148 : -7048138;
        graphics.fill(track[0], thumbY, track[0] + track[2], thumbY + thumb, thumbColor);
        graphics.fill(track[0] + 1, thumbY + 1, track[0] + track[2] - 1, thumbY + thumb - 1, -4681667);
    }

    /** The call sign arrives a moment after the page is built, so it is refreshed until edited. */
    private void compat$syncNameBox() {
        if (this.nameBox == null || this.nameEdited || this.nameBox.isFocused()) {
            return;
        }
        String synced = this.compat$nameValue();
        if (!synced.equals(this.nameBox.getValue())) {
            this.nameBox.setValue(synced);
        }
    }

    /**
     * Takes the two lead values the computer sent, once, while the page is being laid out.
     *
     * <p>An edit already made on this page wins: the page is laid out again when the family is
     * changed, and re-adopting the stored pair there would silently undo the click.</p>
     */
    private void compat$adoptControllerLead() {
        if (this.leadEdited || this.controllerPos == null) {
            return;
        }
        if (!com.qkdream.firecontrolcompat.ControllerLeadClientState
                .matches(this.controllerPos, this.controllerSubLevel)) {
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] lead-adopt pos={} sub={} source=pending (no answer for this computer yet)",
                    this.controllerPos, this.controllerSubLevel);
            return;
        }
        com.qkdream.firecontrolcompat.LeadCannonKind synced =
                com.qkdream.firecontrolcompat.ControllerLeadClientState.kind();
        if (synced != null) {
            this.cannonKind = synced;
        }
        int charge = com.qkdream.firecontrolcompat.ControllerLeadClientState.charge();
        if (charge >= 0) {
            this.bigCharge = charge;
        }
        com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                "[firecontrolcompat] lead-adopt pos={} source=controller kind={} charge={}",
                this.controllerPos, this.cannonKind, this.bigCharge);
    }

    /**
     * Applies the computer's answer if it lands after the page is already up.
     *
     * <p>The server reads the block entity when the computer is opened, which can be a frame or
     * two after this page is built, so a single read at construction time would leave the first
     * frame - and any page built before the answer - showing the defaults.</p>
     */
    /**
     * Fills the launch mode toggle from the answer the computer sent when the page was opened.
     *
     * <p>Like the lead values it arrives a frame or two late, so the page polls for it until the
     * player touches the toggle, and never overwrites a choice made here.</p>
     */
    private void compat$syncLaunchMode() {
        if (this.launchEdited || this.controllerPos == null) {
            return;
        }
        boolean vertical = com.qkdream.firecontrolcompat.BeamLaunchModeState.screenVertical();
        if (vertical != this.verticalLaunch) {
            this.verticalLaunch = vertical;
            if (this.launchButton != null) {
                this.launchButton.setMessage(this.compat$launchText(vertical));
            }
        }
    }

    private void compat$syncLead() {
        if (this.leadEdited || this.controllerPos == null) {
            return;
        }
        if (!com.qkdream.firecontrolcompat.ControllerLeadClientState
                .matches(this.controllerPos, this.controllerSubLevel)) {
            return;
        }
        com.qkdream.firecontrolcompat.LeadCannonKind synced =
                com.qkdream.firecontrolcompat.ControllerLeadClientState.kind();
        int charge = com.qkdream.firecontrolcompat.ControllerLeadClientState.charge();
        boolean changed = false;
        if (synced != null && synced != this.cannonKind) {
            this.cannonKind = synced;
            changed = true;
        }
        if (charge >= 0 && charge != this.bigCharge) {
            this.bigCharge = charge;
            changed = true;
        }
        if (changed) {
            this.rebuildWidgets();
        }
    }

    /** Label of the cannon family the lead is currently solved for. */    private Component compat$cannonKindText() {
        return switch (this.cannonKind) {
            case SMALL_CANNON -> Component.literal("提前量：小炮");
            case MEDIUM_CANNON -> Component.literal("提前量：中炮");
            case BIG_CANNON -> Component.literal("提前量：大炮");
            default -> Component.literal("提前量：机炮");
        };
    }

    /** Next cannon family in the cycle. */
    private com.qkdream.firecontrolcompat.LeadCannonKind compat$nextCannonKind(
            com.qkdream.firecontrolcompat.LeadCannonKind kind) {
        // The autocannon is gone from this cycle: fire control 0.9.3 solves its lead itself.
        return switch (kind) {
            case SMALL_CANNON -> com.qkdream.firecontrolcompat.LeadCannonKind.MEDIUM_CANNON;
            case MEDIUM_CANNON -> com.qkdream.firecontrolcompat.LeadCannonKind.BIG_CANNON;
            default -> com.qkdream.firecontrolcompat.LeadCannonKind.SMALL_CANNON;
        };
    }

    // ------------------------------------------------------------- sonar key rows

    /**
     * One channel's row, named after the key it answers to.
     *
     * <p>All three channels belong to the same single action - "cycle the sonar target" - so they
     * share one label prefix and differ only in the channel they read.</p>
     */
    private Component compat$sonarText(RemoteControllerCompat.Type type) {
        String key = type == RemoteControllerCompat.Type.WIRELESS
                ? "gui.firecontrolcompat.sonar_key_wireless"
                : type == RemoteControllerCompat.Type.ADVANCED
                        ? "gui.firecontrolcompat.sonar_key_advanced"
                        : "gui.firecontrolcompat.sonar_key_normal";
        return Component.translatable(key, this.compat$sonarKeyName(type));
    }

    /** The wireless redstone row: one half of the frequency pair the pick answers to. */
    private Component compat$sonarFrequencyText(boolean first) {
        return Component.translatable(first
                ? "gui.firecontrolcompat.sonar_freq_a"
                : "gui.firecontrolcompat.sonar_freq_b", this.compat$sonarRedstoneName(first));
    }

    /** The item filed as that half, or the pack's own "nothing bound" word. */
    private Component compat$sonarRedstoneName(boolean first) {
        String value = first ? SonarSelectInputState.first() : SonarSelectInputState.second();
        if (value == null || value.isEmpty()) {
            return Component.translatable("gui.firecontrolcompat.select_none");
        }
        return Component.literal(value);
    }

    /** Name of the key a channel of that kind answers to, or the pack's own "unbound" word. */
    private Component compat$sonarKeyName(RemoteControllerCompat.Type type) {
        String signal;
        try {
            signal = type == RemoteControllerCompat.Type.WIRELESS
                    ? SonarSelectInputState.wirelessKey()
                    : type == RemoteControllerCompat.Type.ADVANCED
                            ? SonarSelectInputState.advancedKey()
                            : SonarSelectInputState.normalKey();
        } catch (Throwable ignored) {
            signal = null;
        }
        if (signal == null || signal.isEmpty()) {
            return Component.translatable("gui.firecontrolcompat.select_none");
        }
        try {
            return ControllerKeyMappingCompat.displayKey(type, signal);
        } catch (Throwable ignored) {
            return Component.literal(signal);
        }
    }

    /**
     * Redraws the four rows from the file the server last reported, and marks the one that is
     * waiting for a key. Called from the layout, from the capture and from every frame, because the
     * answer to the refresh request arrives after the page is already up.
     */
    private void compat$refreshSonarKeys() {
        this.compat$tone(this.sonarNormalButton, RemoteControllerCompat.Type.NORMAL);
        this.compat$tone(this.sonarAdvancedButton, RemoteControllerCompat.Type.ADVANCED);
        this.compat$tone(this.sonarWirelessButton, RemoteControllerCompat.Type.WIRELESS);
        if (this.sonarRedstoneFirstButton != null) {
            this.sonarRedstoneFirstButton.setMessage(this.compat$sonarFrequencyText(true));
        }
        if (this.sonarRedstoneSecondButton != null) {
            this.sonarRedstoneSecondButton.setMessage(this.compat$sonarFrequencyText(false));
        }
    }

    /** One key row: its label from the file, green while it is the row waiting for a press. */
    private void compat$tone(CompatToggleButton button, RemoteControllerCompat.Type type) {
        if (button == null) {
            return;
        }
        button.setMessage(this.compat$sonarText(type));
        button.setTone(this.sonarCapturing == type
                ? CompatToggleButton.Tone.GREEN
                : CompatToggleButton.Tone.NORMAL);
    }

    /** Arms one row's capture; clicking it again disarms it. */
    private void compat$captureSonarKey(RemoteControllerCompat.Type type) {
        this.sonarCapturing = this.sonarCapturing == type ? null : type;
        if (this.sonarCapturing != null) {
            this.compat$sonarMessage(type == RemoteControllerCompat.Type.WIRELESS
                    ? "message.firecontrolcompat.select_press_wireless"
                    : type == RemoteControllerCompat.Type.ADVANCED
                            ? "message.firecontrolcompat.select_press_advanced"
                            : "message.firecontrolcompat.select_press_normal");
        }
        this.compat$refreshSonarKeys();
    }

    /**
     * Files the item in hand as one half of the wireless redstone pair, the way 火控学 does it.
     *
     * <p>The pack's own input page has two frequency slots per action ({@code A} and {@code B}),
     * each filed from the item in the hand by {@code SetWirelessFrequencyPayload} - and an empty
     * hand clears that slot. This page had invented a rule of its own instead (main hand = A, off
     * hand = B, one press for both), which is why the row could read {@code stone / -} and why the
     * second half was hard to bind at all. Both halves are filed one at a time now.</p>
     */
    private void compat$bindSonarRedstone(boolean first) {
        if (this.controllerPos == null) {
            return;
        }
        PacketDistributor.sendToServer(new SonarSelectBindPayload(this.controllerPos,
                this.controllerSubLevel,
                first ? SonarSelectBindPayload.SLOT_WIRELESS_FIRST
                        : SonarSelectBindPayload.SLOT_WIRELESS_SECOND,
                ""));
        this.compat$sonarMessage("message.firecontrolcompat.select_redstone_bound");
    }

    /** Files one key for this computer; an empty signal clears it. */
    private void compat$sendSonarKey(RemoteControllerCompat.Type type, String signal) {
        if (this.controllerPos == null) {
            return;
        }
        int slot = type == RemoteControllerCompat.Type.WIRELESS
                ? SonarSelectBindPayload.SLOT_REMOTE_WIRELESS
                : type == RemoteControllerCompat.Type.ADVANCED
                        ? SonarSelectBindPayload.SLOT_REMOTE_ADVANCED
                        : SonarSelectBindPayload.SLOT_REMOTE_NORMAL;
        PacketDistributor.sendToServer(new SonarSelectBindPayload(
                this.controllerPos, this.controllerSubLevel, slot, signal == null ? "" : signal));
    }

    private void compat$sonarMessage(String key) {
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(Component.translatable(key), true);
        }
    }

    /**
     * Takes the next key press for whichever sonar row is waiting for one.
     *
     * <p>Escape, delete and backspace all hand the binding back, the way the sonar page's own
     * capture does, so a wrong key is not something the crew has to live with.</p>
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.sonarCapturing == null) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        RemoteControllerCompat.Type type = this.sonarCapturing;
        this.sonarCapturing = null;
        this.compat$refreshSonarKeys();
        if (keyCode == 256 || keyCode == 259 || keyCode == 261) {
            this.compat$sendSonarKey(type, "");
            this.compat$sonarMessage("message.firecontrolcompat.select_cleared");
            return true;
        }
        String signal = null;
        try {
            signal = ControllerKeyMappingCompat.signalForKeyboardKey(type, keyCode, scanCode);
        } catch (Throwable ignored) {
            signal = null;
        }
        if (signal == null) {
            this.compat$sonarMessage("message.firecontrolcompat.select_key_unbound");
            return true;
        }
        this.compat$sendSonarKey(type, signal);
        this.compat$sonarMessage("message.firecontrolcompat.select_bound");
        return true;
    }
}
