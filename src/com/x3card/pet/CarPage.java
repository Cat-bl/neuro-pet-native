package com.x3card.pet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.OverScroller;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 车况页（同网页版 pages/car）：宠物页左边那一页。
 * 左边（竖向时在上面）一张渐变大卡：俯视的车（发光线框；开着的门、窗、天窗、前舱盖、尾门橙色，车灯、转向灯、氛围灯），四角胎压 / 胎温，
 * 顶上年款和电源，底下挡位、车速、车外温度。右边：能源卡（电量 / 油量圆环 + 总续航）、状态小卡（正常绿、开着 / 异常橙红、打开的功能蓝），
 * "详细数据"按组列出全部车况。宿主有哪些字段就显示哪些，暂时没数据显示"—"；带 * 的是厂商原始读数（不加单位）。
 * 只在数据变了时改（最多 0.3 秒一次）；转向灯闪烁、播放进度只在打灯、放歌时动。
 * 横向右边上下滑，竖向整页上下滑；左右滑交给宠物页切页
 */
final class CarPage implements Pages.Page {
    // ---------- 图标（24×24 线条） ----------
    private static final Map<String, String> ICON = new HashMap<>();
    static {
        String[] a = {
            "door", "M6 3h9l3 3v15H6z M14 12h1.5",
            "window", "M4 6h16v12H4z M4 12h16 M12 6v12",
            "sunroof", "M4 7h16v11H4z M8 10h8v5H8z",
            "ac", "M12 3v18 M4.2 7.5l15.6 9 M4.2 16.5l15.6-9 M9.5 4.5L12 7l2.5-2.5 M9.5 19.5L12 17l2.5 2.5",
            "tyre", "M12 3a9 9 0 1 0 0.01 0z M12 8a4 4 0 1 0 0.01 0z",
            "belt", "M7 3l10 18 M5 21h14 M12 6.5a1.5 1.5 0 1 0 0.01 0",
            "light", "M3 12c0-4 3-7 7-7v14c-4 0-7-3-7-7z M14 8h7 M14 12h7 M14 16h7",
            "air", "M5 19C5 11 11 5 19 5c0 8-6 14-14 14z M5 19l7-7",
            "seat", "M8 3h5l1.5 9H19l1 6H9z M9 18v3 M18 18v3",
            "volume", "M4 9h4l5-4v14l-5-4H4z M16.5 9c1.4 1.6 1.4 4.4 0 6 M19 6.5c2.8 3.2 2.8 7.8 0 11",
            "mode", "M4 16a8 8 0 1 1 16 0 M12 16l4-5 M4 20h16",
            "bolt", "M13 2L5 14h6l-1 8 8-12h-6z",
            "battery", "M3 8h15v8H3z M20 11v2 M6 11h5v2H6z",
            "route", "M6 21a2 2 0 1 0 0-4a2 2 0 1 0 0 4z M18 7a2 2 0 1 0 0-4a2 2 0 1 0 0 4z M8 19h6a3 3 0 0 0 0-6h-4a3 3 0 0 1 0-6h6",
            "gauge", "M5 20V12 M10 20V6 M15 20v-9 M20 20V9",
            "wrench", "M14.5 6.5a4 4 0 0 0-5 5L4 17l3 3 5.5-5.5a4 4 0 0 0 5-5l-2.5 2.5-2.5-.5-.5-2.5z",
            "music", "M9 18V5l11-2v13 M9 18a3 3 0 1 1-6 0a3 3 0 0 1 6 0z M20 16a3 3 0 1 1-6 0a3 3 0 0 1 6 0z",
            "car", "M3 13l2.2-6h13.6l2.2 6v5H3z M3 13h18 M6.5 15.5h2 M15.5 15.5h2 M6 18v2 M18 18v2",
            "shield", "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z",
            "radar", "M12 18.5a1.5 1.5 0 1 0 .01 0z M7.8 14.6a6 6 0 0 1 8.4 0 M4.9 11.7a10 10 0 0 1 14.2 0 M2.3 8.8a13.8 13.8 0 0 1 19.4 0"
        };
        for (int i = 0; i < a.length; i += 2) ICON.put(a[i], a[i + 1]);
    }
    private static Path icon(String k) { return SvgPath.of(ICON.get(k)); }

    // ---------- 配色：浅色 / 深色（同 .card / .card.dark 的变量） ----------
    private static final int BG = 0, PANEL = 1, TEXT = 2, SUB = 3, LINE = 4, SOFT = 5, TRACK = 6, ACCENT = 7, OK = 8, ON = 9, WARN = 10, BAD = 11,
        OK_S = 12, ON_S = 13, WARN_S = 14, BAD_S = 15;
    private static final int[] LIGHT = { 0xffeef1f7, 0xffffffff, 0xff1b2232, 0xff7b8496, 0xffedf0f5, 0xfff2f4f9, 0xffe8ecf3, 0xff4f7cff,
        0xff18a05e, 0xff4f7cff, 0xffe88700, 0xffe5484d,
        Ease.rgba(24, 160, 94, .12f), Ease.rgba(79, 124, 255, .12f), Ease.rgba(232, 135, 0, .14f), Ease.rgba(229, 72, 77, .13f) };
    private static final int[] DARK = { 0xff0b1019, 0xff151c2a, 0xffe9edf5, 0xff8a93a6, 0xff222b3b, 0xff1c2434, 0xff242d3f, 0xff7aa2ff,
        0xff3ccf8e, 0xff7aa2ff, 0xffffb547, 0xffff6b6b,
        Ease.rgba(60, 207, 142, .14f), Ease.rgba(122, 162, 255, .15f), Ease.rgba(255, 181, 71, .15f), Ease.rgba(255, 107, 107, .15f) };
    private static final int GL_LIGHT = 0xffffffff, GL_DARK = 0xff79e5ff; // 车的线：白天白线、夜间青色光
    private static final int[][] HERO = { { 0xff7096ff, 0xff4f67ec, 0xff6a4fe0 }, { 0xff2b3c72, 0xff1b2652, 0xff241c4a } };
    private static final int[] HERO_SH = { Ease.rgba(70, 90, 220, .3f), Ease.rgba(0, 0, 0, .35f) };
    private static final int CARD_SH = Ease.rgba(20, 30, 60, .05f), ENERGY_SH = Ease.rgba(20, 30, 60, .06f);
    private static int col(int i, float k) { return Ease.mix(LIGHT[i], DARK[i], k); }
    // 小卡状态
    private static final int ST_NONE = 0, ST_OK = 1, ST_ON = 2, ST_WARN = 3, ST_BAD = 4;
    private static final Ease DOOR_EASE = new Ease(.3f, .7f, .3f, 1), RING_EASE = new Ease(.2f, .8f, .2f, 1);

    // ---------- 显示用的表 ----------
    private static Map<String, String> M(String... kv) { HashMap<String, String> m = new HashMap<>(); for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]); return m; }
    private static final Map<String, String> POWER = M("off", "OFF", "acc", "ACC", "on", "ON", "ready", "READY", "fakeReady", "READY");
    private static final Map<String, String> PM = M("excellent", "优", "good", "良", "light", "轻度污染", "moderate", "中度污染", "heavy", "重度污染", "severe", "严重污染");
    private static final Map<String, String> ACMODE = M("auto", "自动", "manual", "手动");
    private static final Map<String, String> WIND = M("face", "吹脸", "faceFeet", "脸+脚", "feet", "吹脚", "feetScreen", "脚+前风挡", "screen", "吹前风挡",
        "faceFeetScreen", "脸+脚+前风挡", "faceScreen", "脸+前风挡");
    private static final Map<String, String> AMB_NAME = M("iceBlue", "冰蓝", "coldWhite", "冷白", "warmWhite", "暖白", "redOrange", "红橙", "red", "红色", "redPurple", "红紫", "darkBlue", "深蓝");
    private static final Map<String, Integer> AMB_COLOR = new HashMap<>();
    static {
        String[] k = { "iceBlue", "coldWhite", "warmWhite", "redOrange", "red", "redPurple", "darkBlue" };
        int[] c = { 0xff7fd8ff, 0xffeaf4ff, 0xffffe2b0, 0xffff7a45, 0xffff4d4f, 0xffd64fa8, 0xff4466ff };
        for (int i = 0; i < k.length; i++) AMB_COLOR.put(k[i], c[i]);
    }
    private static final Map<String, String> TYRE_ST = M("normal", "正常", "high", "偏高", "low", "偏低", "warning", "警告", "critical", "严重", "burst", "爆胎", "fault", "异常");
    private static final String[] TYRES = { "frontLeft", "frontRight", "rearLeft", "rearRight" };
    private static final String[] TYRE_NAME = { "左前", "右前", "左后", "右后" };
    private static final String[] DOOR_KEYS = { "body.doors.frontLeft.state", "body.doors.frontRight.state", "body.doors.rearLeft.state", "body.doors.rearRight.state", "body.hood.state", "body.tailgate.state" };
    private static final String[] DOOR_NAMES = { "左前门", "右前门", "左后门", "右后门", "前机舱盖", "尾门" };
    private static final String[] WINDOWS = { "body.windows.frontLeft.position", "body.windows.frontRight.position", "body.windows.rearLeft.position", "body.windows.rearRight.position" };
    private static final Map<String, String> HOST = M("dualPip", "画中画", "floating", "悬浮窗口", "preview", "预览", "fullscreen", "全屏");
    private static final String YEAR_CODE = "ABCDEFGHJKLMNPRSTVWXY123456789"; // VIN 第 10 位是年份码（30 年一轮，按 2010～2039 算）
    private static final String NOTE = "车机数据连接中断，正在重连…", FOOT = "* 厂商原始读数，单位未确认", EMPTY = "没有连上车机数据";
    private static final String[] STAT_L = { "挡位", "km/h", "车外" };

    // ---------- 状态小卡：[编号, 图标, 名称, 用到的字段]，取值在 tile() ----------
    private static final class TileDef {
        final String id, icon, name;
        final String[] fields;
        TileDef(String id, String icon, String name, String... fields) { this.id = id; this.icon = icon; this.name = name; this.fields = fields; }
    }
    private static final TileDef[] TILES = {
        new TileDef("door", "door", "车门", DOOR_KEYS),
        new TileDef("window", "window", "车窗", WINDOWS[0], WINDOWS[1], WINDOWS[2], WINDOWS[3], "body.windows.anyOpen"),
        new TileDef("sunroof", "sunroof", "天窗", "body.sunroof.position", "body.sunshade.position"),
        new TileDef("ac", "ac", "空调", "ac.power", "ac.driverTemperature", "ac.controlMode", "ac.rear.power"),
        new TileDef("tyre", "tyre", "胎压", "tyre.frontLeft.state", "tyre.frontLeft.pressure"),
        new TileDef("belt", "belt", "安全带", "seat.driver.beltFastened"),
        new TileDef("light", "light", "车灯", "light.lowBeam", "light.highBeam", "light.side", "light.daytimeRunning"),
        new TileDef("air", "air", "空气", "environment.pm25.outdoorLevel", "environment.pm25.indoorLevel"),
        new TileDef("seat", "seat", "座椅", "seat.driver.heating", "seat.driver.ventilation", "seat.passenger.heating", "seat.passenger.ventilation", "steeringWheel.heating"),
        new TileDef("volume", "volume", "音量", "audio.volume", "audio.muted"),
        new TileDef("mode", "mode", "驾驶模式", "energy.operationMode", "energy.mode"),
        new TileDef("bolt", "bolt", "充电", "energy.chargingState", "energy.connectorState")
    };

    // ---------- 详细数据：[图标, 组名, 行]；行 [字段, 名称, 格式]，字段以 @ 开头的是组合行（自己取值） ----------
    private static final int F_STR = 0, F_PCT = 1, F_KM = 2, F_ONOFF = 3, F_YESNO = 4, F_TEMP = 5, F_DUR = 6, F_MAP = 7, F_OPEN = 8, F_OPENING = 9, F_SEAT = 10,
        F_RAW = 11, F_RPM = 12, F_UNIT = 13, F_TURN = 14, F_BELT = 15, F_AMB = 16, F_KMH = 17, F_LEVEL = 18, F_DAYS = 19, F_FAULT = 20, F_KEY = 21, F_ANYWIN = 22, F_SPECIAL = 23;
    private static final class RowDef {
        final String f, label;
        final int fmt;
        final Map<String, String> map;
        RowDef(String f, String label, int fmt, Map<String, String> map) { this.f = f; this.label = label; this.fmt = fmt; this.map = map; }
    }
    private static final class GrpDef {
        final String icon, name;
        final RowDef[] rows;
        GrpDef(String icon, String name, RowDef... rows) { this.icon = icon; this.name = name; this.rows = rows; }
    }
    private static RowDef r(String f, String label, int fmt) { return new RowDef(f, label, fmt, null); }
    private static RowDef r(String f, String label, Map<String, String> map) { return new RowDef(f, label, F_MAP, map); }
    private static RowDef s(String f, String label) { return new RowDef(f, label, F_SPECIAL, null); }
    private static final GrpDef[] GROUPS = {
        new GrpDef("battery", "能源",
            r("energy.batteryPercent", "电量", F_PCT), r("energy.electricRange", "纯电续航", F_KM),
            r("energy.fuelPercent", "油量", F_PCT), r("energy.fuelRange", "燃油续航", F_KM),
            r("energy.mode", "能源模式", M("ev", "纯电 EV", "hev", "混动 HEV")),
            r("energy.operationMode", "驾驶模式", M("eco", "经济 ECO", "sport", "运动 SPORT", "comfort", "舒适 COMFORT")),
            r("energy.regenerationLevel", "能量回收", M("standard", "标准", "high", "较强")),
            r("energy.socSave", "SOC 保电", F_ONOFF), r("energy.socTarget", "保电目标", F_PCT), r("energy.simulator", "模拟声浪", F_ONOFF)),
        new GrpDef("bolt", "充电",
            r("energy.chargingState", "充电状态", M("ready", "就绪", "charging", "充电中", "finished", "已充满", "terminated", "已停止")),
            r("energy.connectorState", "充电枪", M("none", "未连接", "ac", "交流", "dc", "直流", "acDc", "交直流", "vtol", "对外放电")),
            r("energy.charging.type", "充电类型", M("default", "默认", "ac", "交流", "vtog", "V2G", "gbDc", "国标直流", "gbNonDc", "国标非直流")),
            r("energy.charging.power", "充电功率*", F_RAW), r("energy.charging.capacity", "充电累计量*", F_RAW),
            r("energy.charging.remainingTime", "充满还要", F_DUR),
            r("energy.wirelessCharging", "无线充电", F_ONOFF),
            r("energy.wirelessChargingState", "手机充电", M("charging", "充电中", "full", "已充满", "fault", "故障", "standby", "待机"))),
        new GrpDef("route", "行程",
            r("driving.speed", "车速", F_KMH), r("driving.gear", "挡位", F_STR), r("power.level", "电源", POWER),
            r("driving.sessionDuration", "本次时长", F_DUR), r("driving.sessionDistance", "本次里程", F_KM),
            r("driving.evSessionDistance", "本次纯电", F_KM), r("driving.hevSessionDistance", "本次混动", F_KM),
            r("driving.journeyDistance", "旅程里程", F_KM), r("driving.totalDistance", "总里程", F_KM),
            r("driving.evTotalDistance", "累计纯电", F_KM), r("driving.hevTotalDistance", "累计混动", F_KM),
            r("driving.averageSpeed", "平均速度*", F_RAW),
            r("body.pedals.accelerator", "油门踏板", F_PCT), r("body.pedals.brake", "制动踏板", F_PCT)),
        new GrpDef("gauge", "能耗",
            r("energy.consumption.averageElectric", "平均电耗", F_UNIT), r("energy.consumption.recentElectric", "近期电耗*", F_RAW),
            r("energy.consumption.instantElectric", "瞬时电耗*", F_RAW), r("energy.consumption.averageFuel", "平均油耗*", F_RAW),
            r("energy.consumption.recentFuel", "近期油耗*", F_RAW), r("energy.consumption.instantFuel", "瞬时油耗", F_UNIT)),
        new GrpDef("ac", "空调与座椅",
            r("ac.power", "空调", F_ONOFF), r("ac.compressor", "压缩机", F_ONOFF), r("ac.driverTemperature", "设定温度", F_TEMP),
            r("ac.indoorTemperature", "车内温度", F_TEMP), r("ac.outdoorTemperature", "车外温度", F_TEMP),
            r("ac.fanLevel", "风量", F_LEVEL), r("ac.circulation", "循环", M("outside", "外循环", "inside", "内循环")),
            r("ac.frontDefrost", "前除霜", F_ONOFF), r("ac.rearDefrost", "后除霜", F_ONOFF),
            r("ac.controlMode", "空调模式", ACMODE), r("ac.windMode", "送风方向", WIND),
            r("ac.rear.power", "后排空调", F_ONOFF), r("ac.rear.temperature", "后排设温", F_TEMP), r("ac.rear.fanLevel", "后排风量", F_LEVEL),
            r("ac.rear.controlMode", "后排模式", ACMODE), r("ac.rear.windMode", "后排送风", WIND),
            r("seat.driver.heating", "主驾加热", F_SEAT), r("seat.passenger.heating", "副驾加热", F_SEAT),
            r("seat.driver.ventilation", "主驾通风", F_SEAT), r("seat.passenger.ventilation", "副驾通风", F_SEAT),
            r("steeringWheel.heating", "方向盘加热", F_ONOFF)),
        new GrpDef("light", "灯光",
            r("light.lowBeam", "近光灯", F_ONOFF), r("light.highBeam", "远光灯", F_ONOFF), r("light.side", "示宽灯", F_ONOFF),
            r("light.daytimeRunning", "日行灯", F_ONOFF), r("light.frontFog", "前雾灯", F_ONOFF), r("light.rearFog", "后雾灯", F_ONOFF),
            r("light.turnSignal.left", "左转向灯", F_TURN), r("light.turnSignal.right", "右转向灯", F_TURN), r("light.hazard", "双闪", F_ONOFF),
            r("light.brake", "刹车灯", M("on", "亮", "flash", "快闪（紧急制动）", "off", "灭")),
            r("light.ambient.color", "氛围灯", F_AMB), r("light.ambient.area", "氛围灯区域", M("front", "前排", "rear", "后排", "all", "全部")),
            r("light.ambient.brightness", "氛围灯亮度", F_STR), r("light.ambient.musicMode", "音乐律动", F_ONOFF), r("light.dynamicColorful", "动态多彩", F_ONOFF)),
        new GrpDef("door", "车身",
            r("body.doors.frontLeft.state", "左前门", F_OPEN), r("body.doors.frontRight.state", "右前门", F_OPEN),
            r("body.doors.rearLeft.state", "左后门", F_OPEN), r("body.doors.rearRight.state", "右后门", F_OPEN),
            r("body.hood.state", "前机舱盖", F_OPEN), r("body.tailgate.state", "尾门", F_OPEN),
            r("body.windows.frontLeft.position", "左前窗", F_OPENING), r("body.windows.frontRight.position", "右前窗", F_OPENING),
            r("body.windows.rearLeft.position", "左后窗", F_OPENING), r("body.windows.rearRight.position", "右后窗", F_OPENING),
            r("body.windows.anyOpen", "车窗", F_ANYWIN),
            r("body.windows.ventilation", "车窗通风", F_YESNO), r("body.windows.halfOpen", "车窗半开", F_YESNO),
            r("body.sunroof.position", "天窗", F_OPENING), r("body.sunshade.position", "遮阳帘", F_OPENING),
            r("body.doors.lockState", "车门锁原码", F_STR), r("body.doors.remoteUnlocking", "远程解锁原码", F_STR)),
        new GrpDef("tyre", "轮胎",
            s("@tyre.frontLeft", "左前"), s("@tyre.frontRight", "右前"), s("@tyre.rearLeft", "左后"), s("@tyre.rearRight", "右后"),
            r("tyre.displayUnit", "胎压单位", F_STR)),
        new GrpDef("shield", "安全",
            r("seat.driver.beltFastened", "主驾安全带", F_BELT),
            r("warning.lowVoltageBatteryFault", "低压供电", F_FAULT),
            r("warning.keyBatteryLow", "钥匙电池", F_KEY),
            // 三种预警是厂商原码：文档不给含义，0 也不能当成安全，照原样显示
            r("warning.bsd.left", "左盲区预警码", F_STR), r("warning.bsd.right", "右盲区预警码", F_STR),
            r("warning.dow.left", "左开门预警码", F_STR), r("warning.dow.right", "右开门预警码", F_STR),
            r("warning.rcta.left", "左后横向预警码", F_STR), r("warning.rcta.right", "右后横向预警码", F_STR)),
        new GrpDef("radar", "雷达（原始读数）", // 厂商原值 0～155，单位没标定，不换算成距离
            r("radar.distance.leftFront", "左前*", F_RAW), r("radar.distance.frontLeftMid", "前左中*", F_RAW), r("radar.distance.frontRightMid", "前右中*", F_RAW),
            r("radar.distance.rightFront", "右前*", F_RAW), r("radar.distance.left", "左侧*", F_RAW), r("radar.distance.right", "右侧*", F_RAW),
            r("radar.distance.leftRear", "左后*", F_RAW), r("radar.distance.middleRear", "后中*", F_RAW), r("radar.distance.rightRear", "右后*", F_RAW)),
        new GrpDef("air", "环境",
            r("environment.pm25.indoor", "车内 PM2.5*", F_RAW), r("environment.pm25.indoorLevel", "车内空气", PM),
            r("environment.pm25.outdoor", "车外 PM2.5*", F_RAW), r("environment.pm25.outdoorLevel", "车外空气", PM),
            r("environment.slope", "坡度", F_PCT),
            r("display.dayNightMode", "昼夜模式", M("day", "白天", "night", "夜间"))),
        new GrpDef("volume", "音频",
            r("audio.volume", "音量", F_STR), r("audio.navigationVolume", "导航音量", F_STR), r("audio.muted", "静音", F_YESNO)),
        new GrpDef("wrench", "保养与动力",
            r("maintenance.distance", "仪表保养里程", F_KM), r("maintenance.days", "仪表保养天数", F_DAYS),
            r("engine.rpm", "发动机转速", F_RPM), r("motor.front.rpm", "前电机转速", F_RPM), r("motor.rear.rpm", "后电机转速", F_RPM),
            r("engine.coolantTemperature", "冷却液*", F_RAW)),
        new GrpDef("music", "音乐",
            s("@media.title", "歌名"), s("@media.artist", "歌手"), s("@media.app", "播放器"), s("@media.state", "状态"), s("@media.progress", "进度"), s("@media.updated", "更新于")),
        new GrpDef("car", "车辆",
            s("@vin.year", "年款"), s("@vin.code", "车架号"), s("@info.app", "宿主版本"), s("@info.api", "接口版本"), s("@info.webview", "内核"),
            s("@info.android", "安卓版本"), s("@info.host", "窗口"))
    };

    // ---------- 俯视的车（viewBox 240×300，车头朝上）：发光线框，半透明车身 + 发光描边，底下一圈雷达环。车长 264、宽 104（约 4.75 m × 1.87 m）。
    // 开着的门连着车窗从前铰链转出去，原来的位置露出门洞；开着的门、窗、天窗、前舱盖、尾门都是橙色。形状只算一次 ----------
    private static final class S {
        static final Path BODY, GREEN, HOOD, TRUNK, ROOF, LN_DIM, AMB, BEAM_LO, BEAM_HI, LN_R, LN_L, LAMP_R, LAMP_L, TL_R, TL_L;
        static final Path[] MIRRORS = new Path[2], WIN = new Path[4];
        static final RectF FLOOR = new RectF(20, 10, 220, 310), RADAR = new RectF(14, 14, 226, 306), RADAR_IN = new RectF(32, 30, 208, 290);
        static final RectF SUN = new RectF(96, 146, 144, 176), FOG1 = new RectF(74, 47, 77, 55), FOG2 = new RectF(163, 47, 166, 55);
        static final RectF TURN_L = new RectF(58.5f, 117.6f, 62.5f, 124.4f), TURN_R = new RectF(177.5f, 117.6f, 181.5f, 124.4f);
        static final RectF[] TYRE = new RectF[4], GAP = new RectF[4], GLASS = new RectF[4], SKIN = new RectF[4];
        static final float[] PX = new float[4], PY = new float[4]; // 车门的前铰链（外侧前角）
        static final Shader LO = new LinearGradient(0, 40, 0, 0, 0xccfff6cf, 0x00fff6cf, Shader.TileMode.CLAMP);
        static final Shader HI = new LinearGradient(0, 34, 0, 0, 0xccfff6cf, 0x00fff6cf, Shader.TileMode.CLAMP);
        static final DashPathEffect DASH = new DashPathEffect(new float[] { 2, 6 }, 0);
        static {
            BODY = sym(120, 26, new float[][] { { 143, 26, 158, 29, 164, 40 }, { 169, 50, 172, 66, 172, 84 }, { 172, 102, 171, 114, 169, 126 }, { 168, 134, 169, 142, 169, 150 },
                { 169, 200 }, { 169, 210, 172, 218, 172, 232 }, { 172, 254, 170, 270, 164, 278 }, { 157, 287, 142, 290, 120, 290 } });
            GREEN = sym(120, 103, new float[][] { { 135, 103, 149, 105, 157, 110 }, { 161, 119, 162, 131, 162, 146 }, { 162, 204 }, { 162, 216, 160, 226, 155, 235 }, { 149, 243, 137, 247, 120, 247 } });
            HOOD = sym(120, 30, new float[][] { { 141, 30, 153, 32, 158, 40 }, { 161, 52, 161, 70, 160, 90 }, { 159, 100, 158, 106, 156.5f, 110.5f }, { 148, 106.5f, 135, 104.5f, 120, 104.5f } });
            TRUNK = sym(120, 249.5f, new float[][] { { 137, 249.5f, 149, 247, 155.5f, 241 }, { 159.5f, 254, 160.5f, 268, 159.5f, 280 }, { 152, 285.5f, 138, 286.5f, 120, 286.5f } });
            ROOF = SvgPath.parse("M102 140L138 140C144.6 140 150 145.4 150 152L150 202C150 208.6 144.6 214 138 214L102 214C95.4 214 90 208.6 90 202L90 152C90 145.4 95.4 140 102 140Z"
                + "M156 111.5L150.5 141M84 111.5L89.5 141M150.5 212L154.5 235.5M89.5 212L85.5 235.5M150 176L162.5 176M90 176L77.5 176");
            LN_DIM = SvgPath.parse("M106 36C104 58 103.5 80 104 103M134 36C136 58 136.5 80 136 103");
            AMB = SvgPath.parse("M80.6 126L80.6 206M159.4 126L159.4 206");
            BEAM_LO = SvgPath.parse("M86 40L54 0L186 0L154 40Z");
            BEAM_HI = SvgPath.parse("M98 34L84 0L156 0L142 34Z");
            MIRRORS[0] = SvgPath.parse("M163 117C170 114 177 114.5 180.5 119.5C181.5 124 175.5 127 163 126.5Z");
            MIRRORS[1] = mir(MIRRORS[0]);
            LN_R = SvgPath.parse("M162 120.5L169.6 120.5M162 176L169.3 176M162 215L169.3 215");
            LN_L = mir(LN_R);
            LAMP_R = SvgPath.parse("M129 30.8C144 31 154.5 33.4 160 41.5");
            LAMP_L = mir(LAMP_R);
            TL_R = SvgPath.parse("M120 286.4C140 286.4 154 284 161.6 276.9");
            TL_L = mir(TL_R);
            WIN[1] = SvgPath.parse("M156.5 112.5C160.5 121 162 132 162 146L162 174.5L151.5 174.5L151.5 143Z");
            WIN[0] = mir(WIN[1]);
            WIN[3] = SvgPath.parse("M151.5 177.5L162 177.5L162 204C162 214 160.5 222 157 229L153 229L151.5 213Z");
            WIN[2] = mir(WIN[3]);
            for (int i = 0; i < 4; i++) {
                boolean right = i % 2 == 1, front = i < 2;
                float tx = right ? 158 : 65, ty = front ? 70 : 212;
                TYRE[i] = new RectF(tx, ty, tx + 17, ty + 48);
                // 车门：前铰链 y、长度；门洞 = 肩线 + 侧窗那一条，门 = 车窗 + 门板，从外侧前角转出去
                float y = front ? 121 : 178, n = front ? 53 : 37;
                GAP[i] = new RectF(right ? 151.5f : 70.5f, y, (right ? 151.5f : 70.5f) + 18, y + n);
                GLASS[i] = new RectF(right ? 152 : 78, y + 1, (right ? 152 : 78) + 10, y + n - 1);
                SKIN[i] = new RectF(right ? 162 : 70.5f, y, (right ? 162 : 70.5f) + 7.5f, y + n);
                PX[i] = right ? 169.5f : 70.5f;
                PY[i] = y;
            }
        }
        private static float mx(float x) { return Math.round((240 - x) * 100) / 100f; }
        /** 左右对称的闭合路径：从中线上的起点沿右半边画到中线上的终点，左半边镜像倒着画回来（[x1,y1,x2,y2,x,y] 曲线或 [x,y] 直线） */
        private static Path sym(float sx, float sy, float[][] segs) {
            Path p = new Path();
            p.moveTo(sx, sy);
            float[][] pts = new float[segs.length + 1][];
            pts[0] = new float[] { sx, sy };
            for (int i = 0; i < segs.length; i++) {
                float[] g = segs[i];
                if (g.length == 6) p.cubicTo(g[0], g[1], g[2], g[3], g[4], g[5]); else p.lineTo(g[0], g[1]);
                pts[i + 1] = new float[] { g[g.length - 2], g[g.length - 1] };
            }
            for (int i = segs.length - 1; i >= 0; i--) {
                float[] g = segs[i], q = pts[i];
                if (g.length == 6) p.cubicTo(mx(g[2]), g[3], mx(g[0]), g[1], mx(q[0]), q[1]); else p.lineTo(mx(q[0]), q[1]);
            }
            p.close();
            return p;
        }
        /** 右边的形状镜像到左边 */
        private static Path mir(Path p) {
            Path m = new Path();
            Matrix t = new Matrix();
            t.setScale(-1, 1, 120, 0);
            p.transform(t, m);
            return m;
        }
    }

    // ================= 页面 =================
    private final Pages.Api api;
    private final App app;
    private final Car src;
    private final float dp;
    private final Body body;
    private final Hero hero;
    private final Fig fig;
    private final Main main;
    private Runnable close;
    private RowV progRow;
    private boolean built, dead, scheduled, inst, progOn, dark, portrait, touched;
    private final Tw darkK = new Tw(0);
    private int gl;
    private float rem;
    private final Runnable renderR = this::render, blinkR = this::schedule, progR = this::progress;

    CarPage(Context c, Pages.Api api) {
        this.api = api;
        app = api.app();
        src = api.car();
        dp = Math.max(1, app.u.dens);
        rem = api.rem();
        dark = api.dark();
        darkK.set(dark ? 1 : 0);
        gl = dark ? GL_DARK : GL_LIGHT;
        body = new Body(c);
        hero = new Hero(c);
        fig = hero.fig;
        main = new Main(c);
        body.addView(hero);
        body.addView(main);
        hero.sizes();
        main.sizes(false);
        if (src == null) { hero.setVisibility(View.GONE); main.setVisibility(View.GONE); return; }
        inst = true; // 第一次填数据不放过渡（同网页版挂上时同步渲染）
        close = src.open(this::schedule);
        render();
        inst = false;
        // 告诉宠物：打开了车况页（年款、总里程有的话宠物会提一句）
        Pages.PageEvent e = new Pages.PageEvent("car", "open");
        int y = vinYear(src.vin);
        e.year = y > 0 ? String.valueOf(y) : null;
        double km = num(g("driving.totalDistance"));
        e.km = Double.isNaN(km) ? 0 : km;
        api.onEvent(e);
    }

    @Override public View view() { return body; }
    @Override public void theme() { applyTheme(); }
    @Override public void destroy() {
        dead = true;
        if (close != null) { close.run(); close = null; }
        app.h.removeCallbacks(renderR);
        app.h.removeCallbacks(blinkR);
        app.h.removeCallbacks(progR);
        body.stop();
        fig.stopBlink();
    }

    // ---------- 取值 ----------
    private Object g(String k) { return src.get(k); }
    private static double num(Object v) { return Car.num(v); }
    private static boolean TRUE(Object v) { return Boolean.TRUE.equals(v); }
    private static boolean gt0(Object v) { return v instanceof Number && ((Number) v).doubleValue() > 0; }
    /** 同 JS 的真假 */
    private static boolean truthy(Object v) {
        if (v == null || v == JSONObject.NULL) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) { double d = ((Number) v).doubleValue(); return d != 0 && !Double.isNaN(d); }
        if (v instanceof String) return !((String) v).isEmpty();
        return true;
    }
    /** 同 JS 的 String(数)：整数不带小数点 */
    private static String js(double v) {
        if (Double.isNaN(v)) return "NaN";
        if (Double.isInfinite(v)) return v > 0 ? "Infinity" : "-Infinity";
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        String s = Double.toString(v);
        return s.indexOf('E') < 0 ? s : new java.math.BigDecimal(s).stripTrailingZeros().toPlainString();
    }
    private static String str(Object v) { return v == null || v == JSONObject.NULL ? "" : v instanceof Number ? js(((Number) v).doubleValue()) : String.valueOf(v); }
    private static String jsOr(Object v) { return truthy(v) ? str(v) : ""; }
    private static double r1(double v) { return Math.round(v * 10) / 10.0; }
    private static String raw(double v) { return js(Math.round(v * 100) / 100.0); } // 原始读数：不加单位
    private static String km(double v) { return js(v >= 100 ? Math.round(v) : r1(v)) + " km"; }
    private static String dur(double s) { long m = Math.round(s / 60); return m >= 60 ? m / 60 + " 小时 " + m % 60 + " 分" : m + " 分钟"; }
    private static String opening(Object v) { return gt0(v) ? Math.round(num(v)) + "%" : "关"; }
    private static String map(Map<String, String> t, Object v) { String m = t.get(str(v)); return m != null && !m.isEmpty() ? m : str(v); }
    private static String clock(long ms) { long s = Math.max(0, Math.round(ms / 1000.0)); return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60; }
    private static String pad(int n) { return (n < 10 ? "0" : "") + n; }
    private static int vinYear(String v) { int i = v != null && v.length() == 17 ? YEAR_CODE.indexOf(Character.toUpperCase(v.charAt(9))) : -1; return i >= 0 ? 2010 + i : 0; }
    private static float shadowR(float blur) { return Math.max(0.1f, (blur / 2 - 0.5f) / 0.57735f); }

    /** 胎压：车机设置的显示单位（没有就用 kPa）；爆胎 / 异常是报警编码 */
    private String pressure(Object v) {
        if ("burst".equals(v) || "fault".equals(v)) return TYRE_ST.get(v);
        Object u0 = g("tyre.displayUnit");
        String u = truthy(u0) ? str(u0) : "kPa";
        double x = num(v);
        return "bar".equals(u) ? String.format(Locale.US, "%.1f", x / 100) + " bar" : "psi".equals(u) ? Math.round(x / 6.894757) + " psi" : Math.round(x) + " kPa";
    }
    /** 0 正常 / 1 提醒（偏高偏低）/ 2 报警 */
    private int tyreBad(String w) {
        Object p = g("tyre." + w + ".pressure"), s = g("tyre." + w + ".state");
        return "burst".equals(p) || "fault".equals(p) || "critical".equals(s) || "warning".equals(s) ? 2 : "high".equals(s) || "low".equals(s) ? 1 : 0;
    }

    // ---------- 刷新：数据变了最多 0.3 秒刷一次 ----------
    private void schedule() {
        if (scheduled || dead) return;
        scheduled = true;
        app.h.postDelayed(renderR, 300);
    }
    private void render() {
        scheduled = false;
        if (dead) return;
        Map<String, String> caps = src.caps;
        if (caps == null) return; // 还没拿到能力目录：等下一次通知
        if (!built) build(caps);
        applyTheme();
        long now = SystemClock.uptimeMillis();
        boolean ch = main.stale != src.stale();
        main.stale = src.stale();
        ch |= energy(now);
        for (TileV t : main.tiles) ch |= tileSync(t, now);
        for (GrpV gr : main.groups) for (RowV rw : gr.rows) {
            String v = value(rw);
            if (v.isEmpty()) v = "—";
            int cls = rw.d.fmt == F_BELT ? ("未系".equals(v) ? 2 : "已系".equals(v) ? 1 : 0) : 0;
            if (!v.equals(rw.val) || cls != rw.cls) { rw.val = v; rw.cls = cls; ch = true; }
        }
        Object ac = g("light.ambient.color");
        Integer sw = ac == null ? null : AMB_COLOR.get(str(ac));
        int swc = sw == null ? 0 : sw;
        if (swc != main.sw) { main.sw = swc; ch = true; }
        if (ch) main.changed();
        // 打灯时 1.7 秒后再看一次（灯灭了就停下闪烁）
        app.h.removeCallbacks(blinkR);
        if (heroSync(now)) app.h.postDelayed(blinkR, 1700);
        // 放歌时每秒走一下进度
        JSONObject m = src.media;
        boolean playing = m != null && TRUE(m.opt("playing")) && m.optLong("durationMs") > 0;
        if (playing && !progOn) { progOn = true; app.h.postDelayed(progR, 1000); }
        else if (!playing && progOn) { progOn = false; app.h.removeCallbacks(progR); }
    }
    private void progress() {
        if (dead || !progOn) return;
        if (progRow != null) {
            String v = value(progRow);
            if (v.isEmpty()) v = "—";
            if (!v.equals(progRow.val)) { progRow.val = v; main.changed(); }
        }
        app.h.postDelayed(progR, 1000);
    }
    /** 车机当前是夜间模式就用深色（没有这个字段时按钟点）；背景、字的颜色 0.8 秒过渡，大卡和车的线直接换 */
    private void applyTheme() {
        boolean d = api.dark();
        if (d == dark) return;
        dark = d;
        darkK.to(d ? 1 : 0, 800, Ease.EASE, SystemClock.uptimeMillis());
        gl = d ? GL_DARK : GL_LIGHT;
        body.invalidate();
        hero.invalidate();
        fig.invalidate();
        main.invalidate();
    }

    /** 宿主有哪些字段就建哪些：小卡、详细数据的行（音乐、车辆两组总是有）；组里一行都没有就不建这组 */
    private static boolean has(Map<String, String> caps, String k) { String m = caps.get(k); return m != null && !m.isEmpty(); }
    private void build(Map<String, String> caps) {
        built = true;
        for (TileDef t : TILES) {
            boolean any = false;
            for (String k : t.fields) if (has(caps, k)) { any = true; break; }
            if (any) main.tiles.add(new TileV(t));
        }
        for (GrpDef gd : GROUPS) {
            GrpV gv = new GrpV(gd);
            for (RowDef rd : gd.rows) {
                String f = rd.f;
                boolean ok;
                if (f.charAt(0) != '@') ok = has(caps, f);
                else if (f.equals("@info.android")) ok = src.hasProp();
                else if (f.startsWith("@tyre.")) { String w = f.substring(6); ok = has(caps, "tyre." + w + ".pressure") || has(caps, "tyre." + w + ".state") || has(caps, "tyre." + w + ".temperature"); }
                else ok = true;
                if (!ok) continue;
                RowV rv = new RowV(rd);
                gv.rows.add(rv);
                if (f.equals("@media.progress")) progRow = rv;
            }
            if (!gv.rows.isEmpty()) main.groups.add(gv);
        }
        main.rings[0].shown = has(caps, "energy.batteryPercent");
        main.rings[1].shown = has(caps, "energy.fuelPercent");
    }

    // ---------- 能源卡 ----------
    private boolean energy(long now) {
        Object b = g("energy.batteryPercent"), f = g("energy.fuelPercent"), er = g("energy.electricRange"), fr = g("energy.fuelRange");
        boolean charging = "charging".equals(g("energy.chargingState"));
        boolean ch = setRing(main.rings[0], b, charging, now);
        ch |= setRing(main.rings[1], f, false, now);
        double total = (b != null && er instanceof Number ? num(er) : 0) + (f != null && fr instanceof Number ? num(fr) : 0);
        boolean any = er != null || fr != null;
        String range = any ? String.valueOf(Math.round(total)) : "—";
        // 不换行空格：只在"·"处换行
        String a = er != null && b != null ? "纯电\u00a0" + Math.round(num(er)) + "\u00a0km" : "", c = fr != null && f != null ? "燃油\u00a0" + Math.round(num(fr)) + "\u00a0km" : "";
        String sub = !a.isEmpty() && !c.isEmpty() ? a + " · " + c : "";
        Object rt = g("energy.charging.remainingTime");
        String chg = charging ? "充电中" + (gt0(rt) ? "，还要 " + dur(num(rt)) : "") : "";
        if (!range.equals(main.eRange) || !sub.equals(main.eSub) || !chg.equals(main.eChg)) { main.eRange = range; main.eSub = sub; main.eChg = chg; ch = true; }
        return ch;
    }
    private boolean setRing(Ring g, Object p, boolean charging, long now) {
        if (!g.shown) return false;
        boolean has = p instanceof Number;
        double v = has ? num(p) : 0;
        float f = has ? (float) (Math.max(0, Math.min(100, v)) / 100) : 0;
        int lv = has ? (v >= 50 ? 1 : v >= 20 ? 2 : 3) : 0; // 圆环颜色：绿 / 橙 / 红（和宠物页胶囊一致）
        String t = has ? Math.round(v) + "%" : "—";
        boolean ch = false;
        if (g.sweep.target() != f) { if (inst) g.sweep.set(f); else g.sweep.to(f, 800, RING_EASE, now); ch = true; }
        if (lv != g.lvl) {
            if (!inst) g.fade.start(g.fade.get(ringColor(g.lvl, darkK.get(now)), now), 400, Ease.EASE, now);
            g.lvl = lv;
            ch = true;
        }
        if (charging != g.charging || !t.equals(g.txt)) { g.charging = charging; g.txt = t; ch = true; }
        return ch;
    }
    private static int ringColor(int lvl, float k) { return lvl == 1 ? col(OK, k) : lvl == 2 ? col(WARN, k) : lvl == 3 ? col(BAD, k) : 0; }

    // ---------- 状态小卡 ----------
    private String tv, ts;
    private int tst;
    private static int icBg(int st, float k) { return col(st == ST_OK ? OK_S : st == ST_ON ? ON_S : st == ST_WARN ? WARN_S : st == ST_BAD ? BAD_S : SOFT, k); }
    private static int icFg(int st, float k) { return col(st == ST_OK ? OK : st == ST_ON ? ON : st == ST_WARN ? WARN : st == ST_BAD ? BAD : SUB, k); }
    private boolean tileSync(TileV t, long now) {
        boolean was = t.shown, fresh = t.fresh;
        t.fresh = false;
        if (!tile(t.d.id)) { if (was) { t.shown = false; return true; } return false; }
        boolean ch = !was;
        t.shown = true;
        if (!tv.equals(t.v) || !ts.equals(t.sub)) { t.v = tv; t.sub = ts; ch = true; }
        if (tst != t.st) {
            if (was && !fresh && !inst) {
                float k = darkK.get(now);
                t.bg.start(t.bg.get(icBg(t.st, k), now), 300, Ease.EASE, now);
                t.fg.start(t.fg.get(icFg(t.st, k), now), 300, Ease.EASE, now);
            }
            t.st = tst;
            ch = true;
        }
        return ch;
    }
    private static String lv(Object v) { double x = num(v); return x == 1 ? "低" : x == 2 ? "高" : ""; }
    private static String join(String... a) { StringBuilder b = new StringBuilder(); for (String s : a) if (s != null && !s.isEmpty()) { if (b.length() > 0) b.append(" · "); b.append(s); } return b.toString(); }
    /** 取一张小卡的值 → tv / ts / tst；宿主没数据时返回 false（不显示） */
    private boolean tile(String id) {
        ts = "";
        tst = ST_NONE;
        switch (id) {
            case "door": {
                int n = 0;
                boolean any = false;
                String first = null;
                StringBuilder names = new StringBuilder();
                for (int i = 0; i < DOOR_KEYS.length; i++) {
                    Object v = g(DOOR_KEYS[i]);
                    if (v != null) any = true;
                    if ("open".equals(v)) { if (n++ > 0) names.append('、'); names.append(DOOR_NAMES[i]); if (first == null) first = DOOR_NAMES[i]; }
                }
                if (!any) return false;
                if (n == 0) { tv = "全部关好"; tst = ST_OK; return true; }
                tv = n > 1 ? n + " 处开着" : first + "开着";
                ts = n > 1 ? names.toString() : "";
                tst = ST_WARN;
                return true;
            }
            case "window": {
                int n = 0;
                boolean def = false;
                for (String k : WINDOWS) { Object v = g(k); if (v != null) def = true; if (gt0(v)) n++; }
                Object any = g("body.windows.anyOpen");
                if (any == null && !def) return false;
                if (n > 0 || truthy(any)) { tv = n > 0 ? "开着 " + n + " 扇" : "有开着的"; tst = ST_WARN; }
                else { tv = "全关"; tst = ST_OK; }
                return true;
            }
            case "sunroof": {
                Object s = g("body.sunroof.position"), sh = g("body.sunshade.position");
                if (s == null && sh == null) return false;
                tv = gt0(s) ? "开 " + Math.round(num(s)) + "%" : "关";
                ts = sh != null ? "遮阳帘 " + opening(sh) : "";
                tst = gt0(s) ? ST_WARN : ST_NONE;
                return true;
            }
            case "ac": {
                Object p = g("ac.power"), t = g("ac.driverTemperature"), f = g("ac.fanLevel"), c = g("ac.circulation");
                if (p == null && t == null) return false;
                String sub = join("auto".equals(g("ac.controlMode")) ? "自动" : "", truthy(c) ? ("inside".equals(c) ? "内循环" : "外循环") : "",
                    f != null ? str(f) + " 档风" : "", TRUE(g("ac.rear.power")) ? "后排开" : "");
                if (Boolean.FALSE.equals(p)) { tv = "关"; return true; }
                tv = t != null ? js(r1(num(t))) + "°" : "开";
                ts = sub;
                tst = ST_ON;
                return true;
            }
            case "tyre": {
                boolean any = false;
                for (String w : TYRES) if (g("tyre." + w + ".state") != null || g("tyre." + w + ".pressure") != null) { any = true; break; }
                if (!any) return false;
                int worst = 0, who = 0;
                for (int i = 0; i < 4; i++) { int b = tyreBad(TYRES[i]); if (b > worst) { worst = b; who = i; } }
                if (worst == 0) { Object p = g("tyre.frontLeft.pressure"); tv = "正常"; ts = p instanceof Number ? pressure(p) : ""; tst = ST_OK; return true; }
                Object p2 = g("tyre." + TYRES[who] + ".pressure"), s2 = g("tyre." + TYRES[who] + ".state");
                String st = s2 == null ? null : TYRE_ST.get(str(s2));
                tv = TYRE_NAME[who] + ("burst".equals(p2) || "fault".equals(p2) ? TYRE_ST.get(p2) : st != null ? st : "异常");
                tst = worst > 1 ? ST_BAD : ST_WARN;
                return true;
            }
            case "belt": {
                Object d = g("seat.driver.beltFastened"); // 只有主驾有数据：副驾、后排扣没扣都报已系，不显示
                if (d == null) return false;
                tv = truthy(d) ? "主驾已系" : "主驾未系";
                tst = truthy(d) ? ST_OK : ST_WARN;
                return true;
            }
            case "light": {
                Object hi = g("light.highBeam"), lo = g("light.lowBeam"), sd = g("light.side"), drl = g("light.daytimeRunning");
                if (hi == null && lo == null && sd == null && drl == null) return false;
                ts = join(truthy(g("light.frontFog")) ? "前雾灯" : "", truthy(g("light.rearFog")) ? "后雾灯" : "", truthy(g("light.hazard")) ? "双闪" : "");
                tv = truthy(hi) ? "远光" : truthy(lo) ? "近光" : truthy(sd) ? "示宽" : truthy(drl) ? "日行灯" : "关";
                tst = truthy(g("light.hazard")) ? ST_WARN : !tv.equals("关") ? ST_ON : ST_NONE;
                return true;
            }
            case "air": {
                Object o = g("environment.pm25.outdoorLevel"), i = g("environment.pm25.indoorLevel");
                if (o == null && i == null) return false;
                Object m = o != null ? o : i;
                tv = (o != null ? "车外 " : "车内 ") + map(PM, m);
                ts = o != null && i != null ? "车内 " + map(PM, i) : "";
                tst = "moderate".equals(m) ? ST_WARN : "heavy".equals(m) || "severe".equals(m) ? ST_BAD : ST_OK;
                return true;
            }
            case "seat": {
                Object dh = g("seat.driver.heating"), dv = g("seat.driver.ventilation"), ph = g("seat.passenger.heating"), pv = g("seat.passenger.ventilation");
                Object wh = g("steeringWheel.heating");
                if (dh == null && dv == null && ph == null && pv == null && wh == null) return false;
                boolean wOn = Boolean.TRUE.equals(wh);
                tv = gt0(dh) ? "主驾加热" + lv(dh) : gt0(dv) ? "主驾通风" + lv(dv) : wOn ? "方向盘加热" : "关";
                ts = join(gt0(ph) ? "副驾加热" + lv(ph) : gt0(pv) ? "副驾通风" + lv(pv) : "", wOn && !tv.equals("方向盘加热") ? "方向盘加热" : "");
                tst = !tv.equals("关") || !ts.isEmpty() ? ST_ON : ST_NONE;
                return true;
            }
            case "volume": {
                Object v = g("audio.volume"), m = g("audio.muted"), n = g("audio.navigationVolume");
                if (v == null && m == null) return false;
                tv = truthy(m) ? "静音" : v != null ? str(v) : "开";
                ts = n != null ? "导航 " + str(n) : "";
                tst = truthy(m) ? ST_WARN : ST_NONE;
                return true;
            }
            case "mode": {
                Object o = g("energy.operationMode"), e = g("energy.mode"), r = g("energy.regenerationLevel");
                if (o == null && e == null) return false;
                String v = join("eco".equals(o) ? "ECO" : "sport".equals(o) ? "SPORT" : "comfort".equals(o) ? "COMFORT" : "", "ev".equals(e) ? "EV" : "hev".equals(e) ? "HEV" : "");
                tv = v.isEmpty() ? "—" : v;
                ts = truthy(r) ? "能量回收" + ("high".equals(r) ? "较强" : "标准") : "";
                tst = "sport".equals(o) ? ST_WARN : ST_ON;
                return true;
            }
            case "bolt": {
                Object s = g("energy.chargingState"), c = g("energy.connectorState"), w = g("energy.wirelessChargingState");
                if (s == null && c == null) return false;
                tv = "charging".equals(s) ? "充电中" : "finished".equals(s) ? "已充满" : "vtol".equals(c) ? "对外放电" : truthy(c) && !"none".equals(c) ? "已插枪" : "未连接";
                ts = "charging".equals(w) ? "手机在充电" : "full".equals(w) ? "手机已充满" : "";
                tst = "charging".equals(s) || "vtol".equals(c) ? ST_ON : "finished".equals(s) ? ST_OK : ST_NONE;
                return true;
            }
        }
        return false;
    }

    // ---------- 详细数据的一行 ----------
    private String value(RowV row) {
        RowDef d = row.d;
        if (d.fmt == F_SPECIAL) return special(d.f);
        Object v = g(d.f);
        if (v == null) return "";
        double x = num(v);
        switch (d.fmt) {
            case F_PCT: return Math.round(x) + "%";
            case F_KM: return km(x);
            case F_ONOFF: return truthy(v) ? "开" : "关";
            case F_YESNO: return truthy(v) ? "是" : "否";
            case F_TEMP: return js(r1(x)) + "°C";
            case F_DUR: return dur(x);
            case F_MAP: return map(d.map, v);
            case F_OPEN: return "open".equals(v) ? "开着" : "关";
            case F_OPENING: return opening(v);
            case F_SEAT: return x == 0 ? "关" : x == 1 ? "低档" : x == 2 ? "高档" : str(v);
            case F_RAW: return raw(x);
            case F_RPM: return Math.round(x) + " rpm"; // 电机转速有正负（倒转、回收）
            case F_UNIT: { String u = src.unit(d.f); return raw(x) + (u != null && !u.isEmpty() ? " " + u : ""); }
            case F_TURN: return src.blinking(d.f.endsWith("left") ? "left" : "right") ? "闪烁中" : "关";
            case F_BELT: return truthy(v) ? "已系" : "未系";
            case F_AMB: { String n = AMB_NAME.get(str(v)); return n != null ? n : str(v); }
            case F_KMH: return Math.round(x) + " km/h";
            case F_LEVEL: return str(v) + " 档";
            case F_DAYS: return str(v) + " 天";
            case F_FAULT: return truthy(v) ? "异常" : "正常";
            case F_KEY: return truthy(v) ? "电量低" : "正常";
            case F_ANYWIN: return truthy(v) ? "有开着的" : "全关";
            default: return str(v);
        }
    }
    /** 组合行：胎压、音乐、车架号、宿主信息 */
    private String special(String f) {
        int dot = f.indexOf('.');
        String kind = f.substring(1, dot), w = f.substring(dot + 1);
        switch (kind) {
            case "tyre": {
                Object p = g("tyre." + w + ".pressure"), s = g("tyre." + w + ".state"), tt = g("tyre." + w + ".temperature");
                ArrayList<String> out = new ArrayList<>(3);
                if (p != null) out.add(pressure(p));
                if (s != null && !("burst".equals(p) || "fault".equals(p))) { String st = TYRE_ST.get(str(s)); out.add(st != null ? st : str(s)); }
                if (tt != null) out.add("胎温* " + raw(num(tt)));
                return android.text.TextUtils.join(" · ", out);
            }
            case "media": {
                JSONObject m = src.media;
                if (m == null) return "";
                switch (w) {
                    case "title": { String t = Car.str(m, "title"); return t == null ? "" : t; }
                    case "artist": { String t = Car.str(m, "artist"); return t == null ? "" : t; }
                    case "app": { String pk = Car.str(m, "packageName"), n = Car.appName(pk); return !n.isEmpty() ? n : pk != null ? pk : ""; }
                    case "state": { Object pl = m.opt("playing"); return Boolean.TRUE.equals(pl) ? "播放中" : Boolean.FALSE.equals(pl) ? "暂停" : ""; }
                    case "progress": {
                        long du = m.optLong("durationMs"), pos = src.mediaPos();
                        return du > 0 ? (pos >= 0 ? clock(pos) + " / " : "") + clock(du) : "";
                    }
                    case "updated": {
                        long at = m.optLong("updatedAt");
                        if (at == 0) return "";
                        Calendar c = Calendar.getInstance();
                        c.setTimeInMillis(at);
                        return pad(c.get(Calendar.HOUR_OF_DAY)) + ":" + pad(c.get(Calendar.MINUTE)) + ":" + pad(c.get(Calendar.SECOND));
                    }
                }
                return "";
            }
            case "vin": {
                String v = src.vin;
                if (v == null || v.isEmpty()) return "";
                if (w.equals("year")) { int y = vinYear(v); return y > 0 ? y + " 款" : ""; }
                return v.length() > 7 ? v.substring(0, 3) + "••••••••••" + v.substring(v.length() - 4) : v; // 屏幕上不露全号
            }
            case "info": {
                if (w.equals("android")) return src.hasProp() && src.androidVer != null ? src.androidVer : "";
                JSONObject i = src.info;
                if (i == null) return "";
                if (w.equals("host")) { Object h = i.opt("hostType"); String n = HOST.get(str(h)); return n != null ? n : jsOr(h); }
                return jsOr(i.opt(w.equals("app") ? "appVersion" : w.equals("api") ? "apiVersion" : "webViewVersion"));
            }
        }
        return "";
    }

    // ---------- 渐变大卡：车、胎压、挡位 / 车速 / 车外温度、年款、电源 ----------
    private boolean heroSync(long now) {
        boolean blink = fig.sync(now, inst);
        Object gear = g("driving.gear"), sp = g("driving.speed"), pw = g("power.level"), ot = g("ac.outdoorTemperature");
        String a = truthy(gear) ? str(gear) : "—", b = sp == null ? "—" : String.valueOf(Math.round(num(sp))), c = ot == null ? "—" : Math.round(num(ot)) + "°";
        int p = "ready".equals(pw) || "fakeReady".equals(pw) || "on".equals(pw) ? 1 : "acc".equals(pw) ? 2 : 0;
        String pt = truthy(pw) ? (POWER.containsKey(str(pw)) ? POWER.get(str(pw)) : "") : "—";
        int y = vinYear(src.vin);
        String yr = y > 0 ? y + " 款" : "";
        Hero h = hero;
        if (!a.equals(h.stat[0]) || !b.equals(h.stat[1]) || !c.equals(h.stat[2]) || p != h.pw || !pt.equals(h.pwText) || !yr.equals(h.year)) {
            h.stat[0] = a; h.stat[1] = b; h.stat[2] = c; h.pw = p; h.pwText = pt; h.year = yr;
            h.invalidate();
        }
        return blink;
    }

    // ================= 小工具 =================
    /** 一种字：大小、行高（line-height: normal）、基线离行顶多远 */
    private static final class T {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float lh, base;
        T(Typeface tf) { p.setTypeface(tf); }
        void size(float px) { p.setTextSize(px); Paint.FontMetrics m = p.getFontMetrics(); lh = m.descent - m.ascent; base = -m.ascent; }
    }
    /** 车况页的数字字体（同网页版 PetDigits：空格 % + , - . / 0-9 : 用 Outfit，别的字退回系统字体）；行高按 Outfit 算 */
    private static final class Mix {
        final Paint d = new Paint(Paint.ANTI_ALIAS_FLAG), s = new Paint(Paint.ANTI_ALIAS_FLAG);
        float lh, base;
        Mix(int weight) {
            d.setTypeface(Fonts.digits);
            d.setFakeBoldText(weight >= 600); // Outfit 只带了 500：要 600 时同浏览器加粗
            s.setTypeface(weight >= 600 ? Fonts.sysBold : weight >= 500 ? Fonts.sysMedium : Fonts.sys);
        }
        /** lineK：line-height 倍数（0 = normal） */
        void size(float px, float lineK) {
            d.setTextSize(px);
            s.setTextSize(px);
            Paint.FontMetrics m = d.getFontMetrics();
            lh = lineK > 0 ? px * lineK : m.descent - m.ascent;
            base = (lh - (m.descent - m.ascent)) / 2 - m.ascent;
        }
        void color(int c) { d.setColor(c); s.setColor(c); }
        static boolean dig(char ch) { return ch == ' ' || ch == '%' || ch >= '+' && ch <= ':'; }
        float width(String t) {
            float w = 0;
            int i = 0, n = t.length();
            while (i < n) {
                boolean dg = dig(t.charAt(i));
                int j = i + 1;
                while (j < n && dig(t.charAt(j)) == dg) j++;
                w += (dg ? d : s).measureText(t, i, j);
                i = j;
            }
            return w;
        }
        void draw(Canvas c, String t, float x, float y) {
            int i = 0, n = t.length();
            while (i < n) {
                boolean dg = dig(t.charAt(i));
                int j = i + 1;
                while (j < n && dig(t.charAt(j)) == dg) j++;
                Paint p = dg ? d : s;
                c.drawText(t, i, j, x, y, p);
                x += p.measureText(t, i, j);
                i = j;
            }
        }
    }
    /** 会过渡的颜色（同 CSS 的 transition: fill / stroke / background）：状态变了时记下当时显示的颜色当起点，目标颜色画的时候按当前主题算 */
    private static final class Fade {
        private int from;
        private long t0 = -1;
        private int ms;
        private Ease e;
        void start(int shown, int ms, Ease e, long now) { from = shown; t0 = now; this.ms = ms; this.e = e; }
        int get(int target, long now) {
            if (t0 < 0) return target;
            float k = (now - t0) / (float) ms;
            if (k >= 1) { t0 = -1; return target; }
            return premix(from, target, e.at(Math.max(0, k)));
        }
        boolean moving(long now) { if (t0 >= 0 && now - t0 >= ms) t0 = -1; return t0 >= 0; }
    }
    /** 颜色按预乘透明度混合（同浏览器：从透明过渡时不带黑色） */
    private static int premix(int a, int b, float k) {
        if (k <= 0) return a;
        if (k >= 1) return b;
        float aa = (a >>> 24) / 255f, ba = (b >>> 24) / 255f, oa = aa + (ba - aa) * k;
        if (oa <= 0.0001f) return 0;
        int r = cl((((a >> 16 & 255) * aa) * (1 - k) + ((b >> 16 & 255) * ba) * k) / oa);
        int g = cl((((a >> 8 & 255) * aa) * (1 - k) + ((b >> 8 & 255) * ba) * k) / oa);
        int bl = cl((((a & 255) * aa) * (1 - k) + ((b & 255) * ba) * k) / oa);
        return Math.round(oa * 255) << 24 | r << 16 | g << 8 | bl;
    }
    private static int cl(float v) { return v < 0 ? 0 : v > 255 ? 255 : Math.round(v); }
    /** 放不下时末尾省略（同 text-overflow: ellipsis） */
    private static String ellipsize(String s, Paint p, float max) {
        if (s.isEmpty() || p.measureText(s) <= max) return s;
        int n = p.breakText(s, true, Math.max(0, max - p.measureText("…")), null);
        return s.substring(0, n) + "…";
    }
    /** 按字断行（同 word-break: break-all）：第一行宽 first，之后 rest */
    private static void breakAll(String s, Paint p, float first, float rest, List<String> out) {
        out.clear();
        int n = s.length(), at = 0;
        float w = first;
        while (at < n) {
            int k = p.breakText(s, at, n, true, Math.max(1, w), null);
            if (k <= 0) k = 1;
            out.add(s.substring(at, at + k));
            at += k;
            w = rest;
        }
        if (out.isEmpty()) out.add("");
    }
    private static final String CLOSE = "，。、；：？！）》」』…·%", OPEN = "（《「『";
    private static boolean cjk(char c) { return c >= 0x2e80 && c <= 0x9fff || c >= 0xf900 && c <= 0xfaff || c >= 0xff00 && c <= 0xffef; }
    /** 能不能在 i 前面断行：空格处、两个汉字之间（不在不换行空格两边、不把标点放到行首） */
    private static boolean canBreak(String s, int i) {
        char a = s.charAt(i - 1), b = s.charAt(i);
        if (a == '\u00a0' || b == '\u00a0') return false;
        if (a == ' ' || b == ' ') return true;
        if (CLOSE.indexOf(b) >= 0 || OPEN.indexOf(a) >= 0) return false;
        return cjk(a) || cjk(b);
    }
    /** 普通断行（同 white-space: normal） */
    private static void wrap(String s, Paint p, float max, List<String> out) {
        out.clear();
        int n = s.length(), start = 0;
        while (start < n) {
            int fit = p.breakText(s, start, n, true, max, null);
            if (fit <= 0) fit = 1;
            if (start + fit >= n) { out.add(s.substring(start)); break; }
            int cut = -1;
            for (int i = start + fit; i > start; i--) if (canBreak(s, i)) { cut = i; break; }
            if (cut < 0) cut = start + fit; // 一个词都放不下：硬断
            int end = cut;
            while (end > start && s.charAt(end - 1) == ' ') end--;
            out.add(s.substring(start, end));
            start = cut;
            while (start < n && s.charAt(start) == ' ') start++;
        }
    }

    // ================= 整页：横向左边大卡、右边能上下滑；竖向整页上下滑。左右滑交给宠物页 =================
    private final class Body extends ViewGroup {
        private final RectF region = new RectF();
        private float scroll, maxScroll;
        private boolean sized;
        private final OverScroller fling;
        private VelocityTracker vt;
        private final int slop, maxV, minV;
        private float downX, downY, lastY;
        private boolean inRegion, dragging;
        // 左右滑换页
        private float swX, swY;
        private long swT;
        private boolean swOn;
        // 滚动条（同浏览器的覆盖式滚动条：滑的时候出来，停下一会儿淡出）
        private long barAt = -1;
        private final Paint bgP = new Paint(), barP = new Paint();
        private final T emptyT = new T(Fonts.sys);

        Body(Context c) {
            super(c);
            setWillNotDraw(false);
            setClipChildren(false); // 大卡的投影画到大卡外面（整页照样被外层裁切）
            ViewConfiguration vc = ViewConfiguration.get(c);
            slop = vc.getScaledTouchSlop();
            maxV = vc.getScaledMaximumFlingVelocity();
            minV = vc.getScaledMinimumFlingVelocity();
            fling = new OverScroller(c);
        }
        void stop() {
            fling.abortAnimation();
            if (vt != null) { vt.recycle(); vt = null; }
        }
        @Override protected void onMeasure(int ws, int hs) { setMeasuredDimension(MeasureSpec.getSize(ws), MeasureSpec.getSize(hs)); }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int W = r - l, H = b - t;
            float R = api.rem();
            boolean p = W < H; // 按面板自己的宽高分横竖（不看整个屏幕）
            if (!sized || R != rem || p != portrait) { sized = true; rem = R; portrait = p; hero.sizes(); main.sizes(p); emptyT.size(.26f * R); }
            if (src == null) return;
            if (!p) {
                float pad = .2f * R, inH = H - 2 * pad;
                int hw = Math.round((W - 2 * pad) * .4f);
                lay(hero, pad, pad, hw, inH);
                float mx = pad + hw + .2f * R;
                int mw = Math.max(1, Math.round(W - pad - mx));
                lay(main, mx, pad, mw, main.layoutFor(mw));
                region.set(mx, pad, mx + mw, pad + inH);
                maxScroll = Math.max(0, main.getHeight() - inH);
            } else {
                float pad = .14f * R, my = pad + 6.4f * R + .14f * R;
                int hw = Math.max(1, Math.round(W - 2 * pad));
                lay(hero, pad, pad, hw, 6.4f * R);
                lay(main, pad, my, hw, main.layoutFor(hw));
                region.set(0, 0, W, H);
                maxScroll = Math.max(0, my + main.getHeight() + pad - H);
            }
            setScroll(scroll);
        }
        private void lay(View v, float x, float y, float w, float h) {
            int X = Math.round(x), Y = Math.round(y), Wd = Math.max(0, Math.round(w)), Ht = Math.max(0, Math.round(h));
            v.measure(MeasureSpec.makeMeasureSpec(Wd, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Ht, MeasureSpec.EXACTLY));
            v.layout(X, Y, X + Wd, Y + Ht);
        }
        /** 滚动只改两块内容的平移（不重画内容） */
        void setScroll(float v) {
            float s = Math.max(0, Math.min(maxScroll, v));
            if (s != scroll) { scroll = s; barAt = SystemClock.uptimeMillis(); invalidate(); }
            main.setTranslationY(-scroll);
            hero.setTranslationY(portrait ? -scroll : 0);
        }
        @Override public void computeScroll() {
            if (fling.computeScrollOffset()) { setScroll(fling.getCurrY()); postInvalidateOnAnimation(); }
        }

        @Override public boolean dispatchTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    swX = e.getX(); swY = e.getY(); swT = SystemClock.uptimeMillis(); swOn = true;
                    // 碰了车况页 = 在看：开场不自动切回时间页
                    if (!touched && src != null) { touched = true; api.onEvent(new Pages.PageEvent("car", "touch")); }
                    break;
                case MotionEvent.ACTION_POINTER_DOWN: swOn = false; break;
                case MotionEvent.ACTION_UP:
                    if (swOn && src != null) {
                        swOn = false;
                        float dx = (e.getX() - swX) / dp, dy = (e.getY() - swY) / dp;
                        long dt = SystemClock.uptimeMillis() - swT;
                        if (dt <= 1500 && Math.abs(dx) >= 60 && Math.abs(dx) >= Math.abs(dy) * 1.2f) {
                            final Pages.PageEvent ev = new Pages.PageEvent("car", "swipe");
                            ev.dir = dx < 0 ? "left" : "right";
                            app.h.post(() -> { if (!dead) api.onEvent(ev); });
                        }
                    }
                    break;
            }
            return super.dispatchTouchEvent(e);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!fling.isFinished()) fling.abortAnimation();
                    downX = x; downY = y; lastY = y;
                    inRegion = region.contains(x, y);
                    dragging = false;
                    if (vt != null) vt.recycle();
                    vt = VelocityTracker.obtain();
                    vt.addMovement(e);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (vt != null) vt.addMovement(e);
                    if (!dragging && inRegion && maxScroll > 0 && Math.abs(y - downY) > slop && Math.abs(y - downY) > Math.abs(x - downX)) {
                        dragging = true;
                        lastY = y;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging) { setScroll(scroll + lastY - y); lastY = y; }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging && vt != null) {
                        vt.addMovement(e);
                        vt.computeCurrentVelocity(1000, maxV);
                        float vy = vt.getYVelocity();
                        if (Math.abs(vy) > minV) { fling.fling(0, Math.round(scroll), 0, Math.round(-vy), 0, 0, 0, Math.round(maxScroll)); postInvalidateOnAnimation(); }
                    }
                    // fall through
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    if (vt != null) { vt.recycle(); vt = null; }
                    return true;
            }
            return true;
        }

        @Override protected boolean drawChild(Canvas c, View child, long t) {
            if (child != main || portrait) return super.drawChild(c, child, t);
            c.save();
            c.clipRect(region); // 横向：右边那一栏滚动时只在自己的框里
            boolean r = super.drawChild(c, child, t);
            c.restore();
            return r;
        }
        @Override protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float k = darkK.get(now);
            bgP.setColor(col(BG, k));
            c.drawRect(0, 0, getWidth(), getHeight(), bgP);
            if (src == null) {
                emptyT.p.setColor(col(SUB, k));
                c.drawText(EMPTY, (getWidth() - emptyT.p.measureText(EMPTY)) / 2, (getHeight() - emptyT.lh) / 2 + emptyT.base, emptyT.p);
            }
            if (darkK.moving(now)) postInvalidateOnAnimation();
        }
        @Override protected void dispatchDraw(Canvas c) {
            super.dispatchDraw(c);
            if (barAt < 0 || maxScroll <= 0) return;
            long t = SystemClock.uptimeMillis() - barAt;
            float a = t < 600 ? 1 : 1 - (t - 600) / 300f;
            if (a <= 0) { barAt = -1; return; }
            float rh = region.height(), th = Math.max(rh * rh / (rh + maxScroll), 18 * dp), ty = region.top + (rh - th) * scroll / maxScroll;
            barP.setColor(Ease.alpha(0x80808080, a));
            c.drawRect(region.right - 3 * dp, ty, region.right, ty + th, barP);
            if (t < 600) postInvalidateDelayed(600 - t); else postInvalidateOnAnimation();
        }
    }

    // ================= 渐变大卡 =================
    private final class Hero extends ViewGroup {
        final Fig fig;
        String year = "", pwText = "—";
        final String[] stat = { "—", "—", "—" };
        int pw; // 0 灰点 / 1 上电（绿点发光）/ 2 ACC（橙点）
        private final Paint shP = new Paint(Paint.ANTI_ALIAS_FLAG), bgP = new Paint(Paint.ANTI_ALIAS_FLAG), circP = new Paint(Paint.ANTI_ALIAS_FLAG),
            chipBg = new Paint(Paint.ANTI_ALIAS_FLAG), dotP = new Paint(Paint.ANTI_ALIAS_FLAG), glowP = new Paint(Paint.ANTI_ALIAS_FLAG), lineP = new Paint();
        private final T nameT = new T(Fonts.sysBold), chipT = new T(Fonts.sysMedium), smallT = new T(Fonts.sys);
        private final Mix statM = new Mix(500);
        private final RectF box = new RectF(), tmp = new RectF();
        private final Path clip = new Path(), c1 = new Path(), c2 = new Path();
        private final float[] iw = new float[3];
        private int gradFor = -1;
        private float nameW, rowH, chipH, statsTop;

        Hero(Context c) {
            super(c);
            setWillNotDraw(false);
            fig = new Fig(c);
            addView(fig);
            nameT.p.setColor(0xffffffff);
            nameT.p.setLetterSpacing(.04f);
            chipT.p.setColor(0xffffffff);
            chipT.p.setLetterSpacing(.02f);
            smallT.p.setColor(0xbfffffff);
            statM.color(0xffffffff);
            chipBg.setColor(0x2effffff);
            lineP.setColor(0x29ffffff);
            glowP.setColor(0xff5fe08a);
        }
        void sizes() {
            float R = rem;
            nameT.size(.32f * R);
            chipT.size(.2f * R);
            smallT.size(.2f * R);
            statM.size(.56f * R, 1.1f);
            nameW = nameT.p.measureText("我的车");
            glowP.setShadowLayer(shadowR(.08f * R), 0, 0, 0xff5fe08a);
            forceLayout();
        }
        @Override protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
            setMeasuredDimension(w, h);
            fig.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            float w = r - l, h = b - t, R = rem;
            fig.layout(0, 0, r - l, b - t);
            box.set(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(box, .32f * R, .32f * R, Path.Direction.CW);
            // 两个淡淡的大圆（同 .hero::before / ::after），裁到圆角卡片里
            c1.reset();
            c1.addCircle(w - .5f * R, .4f * R, 1.9f * R, Path.Direction.CW);
            c1.op(clip, Path.Op.INTERSECT);
            c2.reset();
            c2.addCircle(.5f * R, h + .1f * R, 1.6f * R, Path.Direction.CW);
            c2.op(clip, Path.Op.INTERSECT);
            chipH = chipT.lh + .06f * R;
            rowH = Math.max(nameT.lh, chipH);
            statsTop = h - .16f * R - (dp + .1f * R + statM.lh + smallT.lh);
            float fy = .2f * R + rowH + .06f * R;
            fig.place(.24f * R, fy, w - .48f * R, Math.max(0, statsTop - .06f * R - fy));
            gradFor = -1;
        }
        @Override protected void dispatchDraw(Canvas c) {
            c.save();
            c.clipPath(clip);
            super.dispatchDraw(c);
            c.restore();
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), R = rem;
            int d = dark ? 1 : 0;
            if (gradFor != d) {
                // linear-gradient(160deg, …)：渐变线过卡片中心，长度 = |w·sin| + |h·cos|
                float h = getHeight(), sn = (float) Math.sin(Math.toRadians(160)), cs = (float) -Math.cos(Math.toRadians(160)), len = Math.abs(w * sn) + Math.abs(h * cs);
                bgP.setShader(new LinearGradient(w / 2 - sn * len / 2, h / 2 - cs * len / 2, w / 2 + sn * len / 2, h / 2 + cs * len / 2, HERO[d], new float[] { 0, .55f, 1 }, Shader.TileMode.CLAMP));
                shP.setColor(HERO_SH[d]);
                shP.setShadowLayer(shadowR(.36f * R), 0, .12f * R, HERO_SH[d]);
                gradFor = d;
            }
            float rr = .32f * R;
            c.drawRoundRect(box, rr, rr, shP);
            c.drawRoundRect(box, rr, rr, bgP);
            circP.setColor(0x14ffffff);
            c.drawPath(c1, circP);
            circP.setColor(0x0fffffff);
            c.drawPath(c2, circP);
            // 顶上：我的车 + 年款，右边电源
            float padX = .24f * R, top = .2f * R;
            c.drawText("我的车", padX, top + (rowH - nameT.lh) / 2 + nameT.base, nameT.p);
            float chipY = top + (rowH - chipH) / 2, cr = Math.min(.2f * R, chipH / 2), tb = chipY + .03f * R + chipT.base;
            if (!year.isEmpty()) {
                float x = padX + nameW + .12f * R, cw = .28f * R + chipT.p.measureText(year);
                tmp.set(x, chipY, x + cw, chipY + chipH);
                c.drawRoundRect(tmp, cr, cr, chipBg);
                c.drawText(year, x + .14f * R, tb, chipT.p);
            }
            float cw = .46f * R + chipT.p.measureText(pwText), x = w - padX - cw;
            tmp.set(x, chipY, x + cw, chipY + chipH);
            c.drawRoundRect(tmp, cr, cr, chipBg);
            dotP.setColor(pw == 2 ? 0xffffb547 : 0x80ffffff);
            c.drawCircle(x + .19f * R, chipY + chipH / 2, .05f * R, pw == 1 ? glowP : dotP);
            c.drawText(pwText, x + .32f * R, tb, chipT.p);
            // 底下：挡位、车速、车外温度（space-around）
            c.drawRect(padX, statsTop, w - padX, statsTop + dp, lineP);
            float sum = 0, inner = w - 2 * padX;
            for (int i = 0; i < 3; i++) { iw[i] = Math.max(statM.width(stat[i]), smallT.p.measureText(STAT_L[i])); sum += iw[i]; }
            float gap = (inner - sum) / 3, sx = padX + gap / 2;
            if (gap < 0) { gap = 0; sx = padX + (inner - sum) / 2; }
            float iy = statsTop + dp + .1f * R;
            for (int i = 0; i < 3; i++) {
                float cx = sx + iw[i] / 2;
                statM.draw(c, stat[i], cx - statM.width(stat[i]) / 2, iy + statM.base);
                c.drawText(STAT_L[i], cx - smallT.p.measureText(STAT_L[i]) / 2, iy + statM.lh + smallT.base, smallT.p);
                sx += iw[i] + gap;
            }
        }
    }

    // ================= 俯视的车（铺满大卡，画在车的框里，超出的部分照样画、被大卡的圆角裁掉） =================
    private final class Fig extends View {
        private final Paint pf = new Paint(Paint.ANTI_ALIAS_FLAG), ps = new Paint(Paint.ANTI_ALIAS_FLAG), floorP = new Paint(Paint.ANTI_ALIAS_FLAG),
            beamP = new Paint(Paint.ANTI_ALIAS_FLAG), ttP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Mix tpM = new Mix(600);
        private int floorGl;
        private float s = 1, ox, oy;
        // 目标状态
        private final int[] tyre = new int[4]; // 0 正常 / 1 提醒 / 2 报警
        private final String[] tp = { "", "", "", "" }, tt = { "", "", "", "" };
        private final float[] tpW = new float[4], ttW = new float[4];
        private boolean turnL, turnR, hood, tail;
        private long turnLAt, turnRAt, flashAt;
        private int tl, amb; // 尾灯：0 平常 / 1 亮 / 2 后雾灯 / 3 刹车 / 4 紧急制动快闪；amb 氛围灯颜色（0 不亮）
        // 过渡
        private final Tw beamLo = new Tw(0), beamHi = new Tw(0), hoodK = new Tw(0), tailK = new Tw(0), lampK = new Tw(0), fogK = new Tw(0), sunK = new Tw(0), tlW = new Tw(2.2f);
        private final Tw[] doorK = tws(), doorA = tws(), gapK = tws(), winA = tws();
        private final Fade[] tyreF = fades(), tyreS = fades();
        private final Fade tlC = new Fade(), ambC = new Fade();
        private final Runnable blinkInv = this::invalidate;

        Fig(Context c) {
            super(c);
            pf.setStyle(Paint.Style.FILL);
            ps.setStyle(Paint.Style.STROKE);
            ttP.setTypeface(Fonts.sys);
            ttP.setTextSize(14);
            ttP.setColor(0xbfffffff);
            tpM.size(19, 0);
        }
        private Tw[] tws() { Tw[] a = new Tw[4]; for (int i = 0; i < 4; i++) a[i] = new Tw(0); return a; }
        private Fade[] fades() { Fade[] a = new Fade[4]; for (int i = 0; i < 4; i++) a[i] = new Fade(); return a; }
        void stopBlink() { removeCallbacks(blinkInv); }
        /** 车的框：按 viewBox 240×300 等比放进去、居中 */
        void place(float x, float y, float w, float h) {
            s = Math.max(0.001f, Math.min(w / 240, h / 300));
            ox = x + (w - 240 * s) / 2;
            oy = y + (h - 300 * s) / 2;
            invalidate();
        }
        @Override public boolean onTouchEvent(MotionEvent e) { return false; }

        private boolean tw(Tw t, float v, int ms, Ease e, long now, boolean inst) {
            if (t.target() == v) return false;
            if (inst) t.set(v); else t.to(v, ms, e, now);
            return true;
        }
        private int tyreFill(int st) { return st == 2 ? Ease.alpha(0xffff5a5a, .5f) : st == 1 ? Ease.alpha(0xffffb547, .45f) : Ease.alpha(gl, .12f); }
        private int tyreStroke(int st) { return st == 2 ? 0xffff5a5a : st == 1 ? 0xffffb547 : Ease.alpha(gl, .85f); }
        private int tlColor(int t) { return t == 0 ? Ease.alpha(0xffff5a6a, .55f) : t == 1 ? 0xffff5a6a : 0xffff2a3c; }

        /** 按车况改车上的东西（变了的才过渡）；返回是不是在打转向灯 / 双闪 */
        boolean sync(long now, boolean inst) {
            boolean low = TRUE(g("light.lowBeam")), high = TRUE(g("light.highBeam")), side = TRUE(g("light.side")), drl = TRUE(g("light.daytimeRunning"));
            boolean ch = tw(beamLo, low ? .7f : 0, 400, Ease.EASE, now, inst);
            ch |= tw(beamHi, high ? .9f : 0, 400, Ease.EASE, now, inst);
            ch |= tw(lampK, low || high || drl || side ? 1 : 0, 300, Ease.EASE, now, inst);
            ch |= tw(fogK, TRUE(g("light.frontFog")) ? 1 : 0, 300, Ease.EASE, now, inst);
            Object brk = g("light.brake");
            int t = TRUE(g("light.rearFog")) ? 2 : "flash".equals(brk) ? 4 : "on".equals(brk) ? 3 : low || side ? 1 : 0;
            if (t != tl) {
                if (!inst) tlC.start(tlC.get(tlColor(tl), now), 300, Ease.EASE, now);
                if (t == 4) flashAt = now;
                tl = t;
                ch = true;
            }
            ch |= tw(tlW, t == 2 ? 3 : t >= 3 ? 3.4f : 2.2f, 300, Ease.EASE, now, inst);
            boolean hz = TRUE(g("light.hazard")), bl = hz || src.blinking("left"), br = hz || src.blinking("right");
            if (bl != turnL) { turnL = bl; turnLAt = now; ch = true; }
            if (br != turnR) { turnR = br; turnRAt = now; ch = true; }
            boolean ho = "open".equals(g("body.hood.state")), ta = "open".equals(g("body.tailgate.state"));
            if (ho != hood || ta != tail) { hood = ho; tail = ta; ch = true; }
            ch |= tw(hoodK, ho ? 1 : 0, 300, Ease.EASE, now, inst);
            ch |= tw(tailK, ta ? 1 : 0, 300, Ease.EASE, now, inst);
            Object sr = g("body.sunroof.position");
            ch |= tw(sunK, (float) Math.max(0, Math.min(1, (sr instanceof Number ? num(sr) : 0) / 100)), 400, Ease.EASE, now, inst);
            Object ac = g("light.ambient.color"), ab = g("light.ambient.brightness");
            Integer acol = truthy(ac) ? AMB_COLOR.get(str(ac)) : null;
            int a = acol != null && !(ab instanceof Number && num(ab) == 0) ? acol : 0;
            if (a != amb) { if (!inst) ambC.start(ambC.get(amb, now), 500, Ease.EASE, now); amb = a; ch = true; }
            for (int i = 0; i < 4; i++) {
                String w = TYRES[i];
                Object p = g("tyre." + w + ".pressure"), tt0 = g("tyre." + w + ".temperature");
                int b = tyreBad(w);
                if (b != tyre[i]) {
                    if (!inst) { tyreF[i].start(tyreF[i].get(tyreFill(tyre[i]), now), 300, Ease.EASE, now); tyreS[i].start(tyreS[i].get(tyreStroke(tyre[i]), now), 300, Ease.EASE, now); }
                    tyre[i] = b;
                    ch = true;
                }
                String sp = p == null ? "" : pressure(p), st = tt0 == null ? "" : "胎温 " + raw(num(tt0));
                if (!sp.equals(tp[i])) { tp[i] = sp; tpW[i] = tpM.width(sp); ch = true; }
                if (!st.equals(tt[i])) { tt[i] = st; ttW[i] = ttP.measureText(st); ch = true; }
                boolean op = "open".equals(g("body.doors." + w + ".state"));
                ch |= tw(doorK[i], op ? 1 : 0, 450, DOOR_EASE, now, inst);
                ch |= tw(doorA[i], op ? 1 : 0, 200, Ease.EASE, now, inst);
                ch |= tw(gapK[i], op ? 1 : 0, 300, Ease.EASE, now, inst);
                Object wp = g("body.windows." + w + ".position");
                ch |= tw(winA[i], gt0(wp) ? (float) Math.min(1, .35 + num(wp) / 150) : 0, 300, Ease.EASE, now, inst);
            }
            if (ch) invalidate();
            return bl || br;
        }

        @Override protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            int g = gl;
            c.save();
            c.translate(ox, oy);
            c.scale(s, s);
            // 地面的光晕、雷达环
            if (floorGl != g || floorP.getShader() == null) {
                RadialGradient rg = new RadialGradient(0, 0, 1, Ease.alpha(g, .2f), g & 0xffffff, Shader.TileMode.CLAMP);
                Matrix m = new Matrix();
                m.setScale(100, 150);
                m.postTranslate(120, 160);
                rg.setLocalMatrix(m);
                floorP.setShader(rg);
                floorGl = g;
            }
            c.drawOval(S.FLOOR, floorP);
            ps.setStrokeCap(Paint.Cap.BUTT);
            ps.setStrokeJoin(Paint.Join.MITER);
            ps.setStrokeWidth(1);
            ps.setPathEffect(S.DASH);
            ps.setColor(Ease.alpha(g, .2f));
            c.drawOval(S.RADAR, ps);
            ps.setPathEffect(null);
            ps.setColor(Ease.alpha(g, .12f));
            c.drawOval(S.RADAR_IN, ps);
            // 近光、远光
            float a = beamLo.get(now);
            if (a > 0) { beamP.setShader(S.LO); beamP.setAlpha(Math.round(a * 255)); c.drawPath(S.BEAM_LO, beamP); }
            a = beamHi.get(now);
            if (a > 0) { beamP.setShader(S.HI); beamP.setAlpha(Math.round(a * 255)); c.drawPath(S.BEAM_HI, beamP); }
            // 轮胎：胎压异常橙 / 红
            ps.setStrokeWidth(1.2f);
            for (int i = 0; i < 4; i++) {
                pf.setColor(tyreF[i].get(tyreFill(tyre[i]), now));
                c.drawRoundRect(S.TYRE[i], 6, 6, pf);
                ps.setColor(tyreS[i].get(tyreStroke(tyre[i]), now));
                c.drawRoundRect(S.TYRE[i], 6, 6, ps);
            }
            // 后视镜
            ps.setStrokeWidth(1.1f);
            for (Path p : S.MIRRORS) {
                pf.setColor(Ease.alpha(g, .1f));
                c.drawPath(p, pf);
                ps.setColor(Ease.alpha(g, .8f));
                c.drawPath(p, ps);
            }
            // 车身：外面一圈柔光 + 半透明车身 + 描边
            ps.setStrokeJoin(Paint.Join.ROUND);
            ps.setStrokeWidth(6);
            ps.setColor(Ease.alpha(g, .16f));
            c.drawPath(S.BODY, ps);
            ps.setStrokeJoin(Paint.Join.MITER);
            pf.setColor(Ease.alpha(g, .07f));
            c.drawPath(S.BODY, pf);
            ps.setStrokeWidth(1.6f);
            ps.setColor(g);
            c.drawPath(S.BODY, ps);
            // 前机舱盖、尾门
            part(c, S.HOOD, hoodK.get(now), hood, g);
            part(c, S.TRUNK, tailK.get(now), tail, g);
            // 肩线、引擎盖上的线
            ps.setStrokeCap(Paint.Cap.ROUND);
            ps.setStrokeWidth(1.1f);
            ps.setColor(Ease.alpha(g, .55f));
            c.drawPath(S.LN_R, ps);
            c.drawPath(S.LN_L, ps);
            ps.setStrokeWidth(1);
            ps.setColor(Ease.alpha(g, .3f));
            c.drawPath(S.LN_DIM, ps);
            // 前雾灯
            pf.setColor(premix(Ease.alpha(g, .25f), 0xfffff1a8, fogK.get(now)));
            c.drawRoundRect(S.FOG1, 1.5f, 1.5f, pf);
            c.drawRoundRect(S.FOG2, 1.5f, 1.5f, pf);
            // 大灯
            ps.setStrokeWidth(2.2f);
            ps.setColor(premix(Ease.alpha(g, .45f), 0xfffffbe3, lampK.get(now)));
            c.drawPath(S.LAMP_R, ps);
            c.drawPath(S.LAMP_L, ps);
            ps.setStrokeCap(Paint.Cap.BUTT);
            // 玻璃
            pf.setColor(Ease.alpha(g, .14f));
            c.drawPath(S.GREEN, pf);
            ps.setStrokeWidth(1.1f);
            ps.setColor(Ease.alpha(g, .8f));
            c.drawPath(S.GREEN, ps);
            // 车顶、门缝的线
            ps.setStrokeCap(Paint.Cap.ROUND);
            ps.setColor(Ease.alpha(g, .55f));
            c.drawPath(S.ROOF, ps);
            ps.setStrokeCap(Paint.Cap.BUTT);
            // 开着的车窗
            ps.setStrokeWidth(1.2f);
            for (int i = 0; i < 4; i++) {
                a = winA[i].get(now);
                if (a <= 0) continue;
                pf.setColor(Ease.alpha(0xffffa94d, .5f * a));
                c.drawPath(S.WIN[i], pf);
                ps.setColor(Ease.alpha(0xffffb35c, a));
                c.drawPath(S.WIN[i], ps);
            }
            // 天窗：从前沿往后展开
            a = sunK.get(now);
            if (a > 0) {
                c.save();
                c.scale(1, a, 120, 146);
                pf.setColor(Ease.alpha(0xffffa94d, .5f));
                c.drawRoundRect(S.SUN, 7, 7, pf);
                ps.setColor(0xffffb35c);
                c.drawRoundRect(S.SUN, 7, 7, ps);
                c.restore();
            }
            // 氛围灯（在侧窗里描边）
            int ac = ambC.get(amb, now);
            if (ac >>> 24 != 0) {
                ps.setStrokeCap(Paint.Cap.ROUND);
                ps.setStrokeWidth(2.2f);
                ps.setColor(Ease.alpha(ac, .9f));
                c.drawPath(S.AMB, ps);
                ps.setStrokeCap(Paint.Cap.BUTT);
            }
            // 车门：门洞，门连着车窗从前铰链转出去
            for (int i = 0; i < 4; i++) {
                float gk = gapK[i].get(now);
                if (gk > 0) { pf.setColor(Ease.alpha(0xffffa94d, .18f * gk)); c.drawRoundRect(S.GAP[i], 3, 3, pf); }
                a = doorA[i].get(now);
                if (a <= 0) continue;
                c.save();
                c.rotate((i % 2 == 0 ? 40 : -40) * doorK[i].get(now), S.PX[i], S.PY[i]);
                pf.setColor(Ease.alpha(0xffffa94d, .15f * a));
                c.drawRoundRect(S.GLASS[i], 2, 2, pf);
                ps.setStrokeWidth(1);
                ps.setColor(Ease.alpha(0xffffb35c, .6f * a));
                c.drawRoundRect(S.GLASS[i], 2, 2, ps);
                pf.setColor(Ease.alpha(0xffffa94d, .35f * a));
                c.drawRoundRect(S.SKIN[i], 2.5f, 2.5f, pf);
                ps.setStrokeWidth(1.4f);
                ps.setColor(Ease.alpha(0xffffb35c, a));
                c.drawRoundRect(S.SKIN[i], 2.5f, 2.5f, ps);
                c.restore();
            }
            // 尾灯：亮 / 后雾灯 / 刹车更亮更粗，紧急制动快闪
            int tc = tlC.get(tlColor(tl), now);
            if (tl == 4) tc = Ease.alpha(tc | 0xff000000, (now - flashAt) % 500 < 250 ? 1 : .2f);
            ps.setStrokeCap(Paint.Cap.ROUND);
            ps.setStrokeWidth(tlW.get(now));
            ps.setColor(tc);
            c.drawPath(S.TL_R, ps);
            c.drawPath(S.TL_L, ps);
            ps.setStrokeCap(Paint.Cap.BUTT);
            // 转向灯：0.8 秒一亮一灭
            pf.setColor(0xffffb21f);
            if (turnL && (now - turnLAt) % 800 < 400) c.drawOval(S.TURN_L, pf);
            if (turnR && (now - turnRAt) % 800 < 400) c.drawOval(S.TURN_R, pf);
            // 胎压、胎温：左边两个右对齐到 54，右边两个从 186 起
            for (int i = 0; i < 4; i++) {
                boolean left = i % 2 == 0;
                float y = i < 2 ? 90 : 232;
                if (!tp[i].isEmpty()) {
                    tpM.color(tyre[i] == 2 ? 0xffff9a9a : tyre[i] == 1 ? 0xffffd166 : 0xffffffff);
                    tpM.draw(c, tp[i], left ? 54 - tpW[i] : 186, y);
                }
                if (!tt[i].isEmpty()) c.drawText(tt[i], left ? 54 - ttW[i] : 186, y + 17, ttP);
            }
            c.restore();
            // 还在过渡就下一帧接着画；只剩闪烁时到下一次亮灭再画
            boolean moving = beamLo.moving(now) | beamHi.moving(now) | hoodK.moving(now) | tailK.moving(now) | lampK.moving(now) | fogK.moving(now) | sunK.moving(now)
                | tlW.moving(now) | tlC.moving(now) | ambC.moving(now);
            for (int i = 0; i < 4; i++) moving |= doorK[i].moving(now) | doorA[i].moving(now) | gapK[i].moving(now) | winA[i].moving(now) | tyreF[i].moving(now) | tyreS[i].moving(now);
            removeCallbacks(blinkInv);
            if (moving) postInvalidateOnAnimation();
            else {
                long next = Long.MAX_VALUE;
                if (turnL) next = Math.min(next, 400 - (now - turnLAt) % 400);
                if (turnR) next = Math.min(next, 400 - (now - turnRAt) % 400);
                if (tl == 4) next = Math.min(next, 250 - (now - flashAt) % 250);
                if (next != Long.MAX_VALUE) postDelayed(blinkInv, Math.max(16, next));
            }
        }
        /** 前机舱盖 / 尾门：开着时填橙色、描边变橙 */
        private void part(Canvas c, Path p, float k, boolean open, int g) {
            if (k > 0) { pf.setColor(Ease.alpha(0xffffa94d, .4f * k)); c.drawPath(p, pf); }
            ps.setStrokeWidth(open ? 1.2f : 1);
            ps.setColor(premix(Ease.alpha(g, .3f), 0xffffb35c, k));
            c.drawPath(p, ps);
        }
    }

    // ================= 右边（竖向在大卡下面）：能源卡、状态小卡、详细数据 =================
    private static final class Ring {
        final String label;
        boolean shown = true, charging;
        int lvl = 1; // 没填数据前同网页版：整圈绿色
        String txt = "—";
        final Tw sweep = new Tw(1);
        final Fade fade = new Fade();
        float txtW, labW;
        Ring(String label) { this.label = label; }
    }
    private static final class TileV {
        final TileDef d;
        final Path icon;
        boolean shown, fresh = true;
        String v = "", sub = "", vE = "", subE = "";
        int st;
        float x, y, w, h;
        final Fade bg = new Fade(), fg = new Fade();
        TileV(TileDef d) { this.d = d; icon = CarPage.icon(d.icon); }
    }
    private static final class RowV {
        final RowDef d;
        final boolean amb;
        String val = "—", laid;
        int cls; // 0 普通 / 1 绿（已系）/ 2 红（未系）
        float laidW = -1, y, h;
        final ArrayList<String> lines = new ArrayList<>();
        float[] lw = new float[0];
        RowV(RowDef d) { this.d = d; amb = d.fmt == F_AMB; }
    }
    private static final class GrpV {
        final GrpDef d;
        final Path icon;
        final ArrayList<RowV> rows = new ArrayList<>();
        float x, y, w, h;
        GrpV(GrpDef d) { this.d = d; icon = CarPage.icon(d.icon); }
    }

    private final class Main extends View {
        boolean stale;
        final Ring[] rings = { new Ring("电量"), new Ring("油量") };
        String eRange = "—", eSub = "", eChg = "";
        final ArrayList<TileV> tiles = new ArrayList<>();
        final ArrayList<GrpV> groups = new ArrayList<>();
        int sw; // 氛围灯色块（0 = 透明）
        private final T noteT = new T(Fonts.sys), ringS = new T(Fonts.sys), ringSB = new T(Fonts.sysBold), rSmall = new T(Fonts.sys), rKm = new T(Fonts.sys),
            rSub = new T(Fonts.sys), rChg = new T(Fonts.sysBold), tS = new T(Fonts.sys), tB = new T(Fonts.sysBold), tE = new T(Fonts.sys),
            secT = new T(Fonts.sysBold), h3T = new T(Fonts.sysBold), labT = new T(Fonts.sys), valT = new T(Fonts.sysBold), footT = new T(Fonts.sys);
        private final Mix ringB = new Mix(500), rangeB = new Mix(500);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), fillE = new Paint(Paint.ANTI_ALIAS_FLAG), fillC = new Paint(Paint.ANTI_ALIAS_FLAG),
            arc = new Paint(Paint.ANTI_ALIAS_FLAG), ic = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rr = new RectF();
        private float W, H, ringSize, ringMr, noteH, eY, eH, rangeX, rangeH, eRangeW, secY, secTW, gridY, footY, h3H;
        private final ArrayList<String> subL = new ArrayList<>(), chgL = new ArrayList<>();
        private float[] hs = new float[0];

        Main(Context c) {
            super(c);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeCap(Paint.Cap.ROUND);
            ic.setStyle(Paint.Style.STROKE);
            ic.setStrokeCap(Paint.Cap.ROUND);
            ic.setStrokeJoin(Paint.Join.ROUND);
            ic.setStrokeWidth(1.8f);
        }
        void sizes(boolean p) {
            float R = rem;
            noteT.size(.22f * R);
            ringB.size((p ? .36f : .4f) * R, 1);
            ringS.size(.19f * R);
            ringSB.size(.19f * R);
            rSmall.size(.21f * R);
            rangeB.size((p ? .6f : .7f) * R, 1.05f);
            rKm.size(.24f * R);
            rSub.size(.21f * R);
            rChg.size(.22f * R);
            tS.size(.19f * R);
            tB.size(.26f * R);
            tE.size(.18f * R);
            secT.size(.22f * R);
            h3T.size(.26f * R);
            labT.size(.23f * R);
            valT.size(.23f * R);
            footT.size(.18f * R);
            ringSize = (p ? 1.3f : 1.5f) * R;
            ringMr = (p ? .14f : .2f) * R;
            h3H = Math.max(.3f * R, h3T.lh);
            fillE.setShadowLayer(shadowR(.2f * R), 0, .04f * R, ENERGY_SH);
            fillC.setShadowLayer(shadowR(.14f * R), 0, .03f * R, CARD_SH);
            for (GrpV g : groups) for (RowV r : g.rows) r.laidW = -1;
        }
        @Override protected void onMeasure(int ws, int hs) { setMeasuredDimension(MeasureSpec.getSize(ws), MeasureSpec.getSize(hs)); }
        @Override public boolean onTouchEvent(MotionEvent e) { return false; }

        /** 内容变了：重新排（高度变了让外面重新布局） */
        void changed() {
            if (W > 0) {
                float old = H;
                if (Math.abs(layoutFor(W) - old) > .5f) body.requestLayout();
            }
            invalidate();
        }
        /** 按宽度排好全部内容，返回总高度 */
        float layoutFor(float w) {
            W = w;
            float R = rem, y = 0;
            noteH = noteT.lh + .2f * R;
            if (stale) y = noteH + .12f * R;
            // 能源卡
            eY = y;
            float rw = 0;
            boolean anyRing = false;
            for (Ring g : rings) {
                if (!g.shown) continue;
                rw += ringSize + ringMr;
                anyRing = true;
                g.txtW = ringB.width(g.txt);
                g.labW = (g.charging ? ringSB : ringS).p.measureText(g.label);
            }
            rangeX = .22f * R + rw;
            float rangeW = Math.max(1, W - .22f * R - rangeX);
            wrap(eSub, rSub.p, rangeW, subL);
            wrap(eChg, rChg.p, rangeW, chgL);
            eRangeW = rangeB.width(eRange);
            rangeH = rSmall.lh + rangeB.lh + subL.size() * rSub.lh + (chgL.isEmpty() ? 0 : .04f * R + chgL.size() * rChg.lh);
            eH = .36f * R + Math.max(anyRing ? ringSize : 0, rangeH);
            y += eH + .14f * R;
            // 状态小卡：横向 auto-fill（每格至少 2.4rem），竖向两列；一行里一样高
            float gap = .12f * R;
            int n = portrait ? 2 : Math.max(1, (int) Math.floor((W + gap) / (2.4f * R + gap)));
            float cw = (W - (n - 1) * gap) / n, txW = Math.max(1, cw - 1.02f * R);
            int col = 0, rowStart = 0;
            float rowY = y, rowH = 0;
            boolean any = false;
            for (int i = 0; i < tiles.size(); i++) {
                TileV t = tiles.get(i);
                if (!t.shown) continue;
                if (col == n) { rowHeight(rowStart, i, rowH); rowY += rowH + gap; rowH = 0; col = 0; rowStart = i; }
                t.x = col * (cw + gap);
                t.y = rowY;
                t.w = cw;
                t.vE = ellipsize(t.v, tB.p, txW);
                t.subE = ellipsize(t.sub, tE.p, txW);
                rowH = Math.max(rowH, .28f * R + Math.max(.56f * R, tS.lh + tB.lh + (t.sub.isEmpty() ? 0 : tE.lh)));
                col++;
                any = true;
            }
            if (any) { rowHeight(rowStart, tiles.size(), rowH); y = rowY + rowH; }
            // 详细数据
            y += .22f * R;
            secY = y;
            secTW = secT.p.measureText("详细数据");
            y += secT.lh + .12f * R;
            gridY = y;
            y += columns();
            footY = y;
            y += .02f * R + footT.lh + .14f * R;
            H = y;
            return y;
        }
        private void rowHeight(int from, int to, float h) { for (int i = from; i < to; i++) tiles.get(i).h = h; }
        /** 详细数据按栏排（同 column-width: 3.5rem; column-gap: .14rem）：每组整块不拆开，各栏高度尽量平均 */
        private float columns() {
            float R = rem, gap = .14f * R, mb = .14f * R;
            int n = Math.max(1, (int) Math.floor((W + gap) / (3.5f * R + gap)));
            float cw = (W - (n - 1) * gap) / n, inner = cw - .36f * R, minW = valT.p.measureText("中");
            if (hs.length != groups.size()) hs = new float[groups.size()];
            for (int gi = 0; gi < groups.size(); gi++) {
                GrpV g = groups.get(gi);
                float y = .14f * R + h3H + .08f * R;
                for (RowV r : g.rows) {
                    float avail = Math.max(minW, inner - labT.p.measureText(r.d.label) - .14f * R);
                    if (!r.val.equals(r.laid) || r.laidW != avail) {
                        breakAll(r.val, valT.p, r.amb ? Math.max(minW, avail - .28f * R) : avail, avail, r.lines);
                        if (r.lw.length != r.lines.size()) r.lw = new float[r.lines.size()];
                        for (int i = 0; i < r.lw.length; i++) r.lw[i] = valT.p.measureText(r.lines.get(i));
                        r.laid = r.val;
                        r.laidW = avail;
                    }
                    r.y = y;
                    r.h = dp + .14f * R + r.lines.size() * labT.lh;
                    y += r.h;
                }
                g.w = cw;
                g.h = y + .08f * R;
                hs[gi] = g.h + mb;
            }
            if (groups.isEmpty()) return 0;
            float total = 0, mx = 0;
            for (float h : hs) { total += h; mx = Math.max(mx, h); }
            float colH = Math.max(mx, total / n);
            for (int it = 0; it < 64; it++) {
                int cols = 1;
                float cur = 0, over = Float.MAX_VALUE;
                for (float h : hs) {
                    if (cur > 0 && cur + h > colH + .5f) { over = Math.min(over, cur + h - colH); cols++; cur = h; }
                    else cur += h;
                }
                if (cols <= n || over == Float.MAX_VALUE) break;
                colH += over;
            }
            int c = 0;
            float cur = 0;
            for (int gi = 0; gi < groups.size(); gi++) {
                GrpV g = groups.get(gi);
                if (cur > 0 && cur + hs[gi] > colH + .5f) { c++; cur = 0; }
                g.x = c * (cw + gap);
                g.y = gridY + cur;
                cur += hs[gi];
            }
            return colH;
        }
        private void drawIcon(Canvas c, Path p, float x, float y, float size) {
            c.save();
            c.translate(x, y);
            c.scale(size / 24, size / 24);
            c.drawPath(p, ic);
            c.restore();
        }

        @Override protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float k = darkK.get(now), R = rem;
            boolean anim = darkK.moving(now);
            int panel = col(PANEL, k), text = col(TEXT, k), sub = col(SUB, k);
            // 断线提示
            if (stale) {
                fill.setColor(col(BAD_S, k));
                rr.set(0, 0, W, noteH);
                c.drawRoundRect(rr, .16f * R, .16f * R, fill);
                noteT.p.setColor(col(BAD, k));
                c.drawText(NOTE, .16f * R, .1f * R + noteT.base, noteT.p);
            }
            // 能源卡：电量 / 油量圆环 + 总续航
            fillE.setColor(panel);
            rr.set(0, eY, W, eY + eH);
            c.drawRoundRect(rr, .26f * R, .26f * R, fillE);
            float x = .22f * R, cy = eY + eH / 2;
            for (Ring g : rings) {
                if (!g.shown) continue;
                float cx = x + ringSize / 2, rad = .42f * ringSize;
                arc.setStrokeWidth(.09f * ringSize);
                arc.setColor(col(TRACK, k));
                c.drawCircle(cx, cy, rad, arc);
                float f = g.sweep.get(now);
                int fc = g.fade.get(ringColor(g.lvl, k), now);
                anim |= g.sweep.moving(now) | g.fade.moving(now);
                if (f > 0 && fc >>> 24 != 0) {
                    arc.setColor(fc);
                    rr.set(cx - rad, cy - rad, cx + rad, cy + rad);
                    c.drawArc(rr, -90, 360 * f, false, arc);
                }
                T lt = g.charging ? ringSB : ringS;
                float top = cy - (ringB.lh + .04f * R + lt.lh) / 2;
                ringB.color(text);
                ringB.draw(c, g.txt, cx - g.txtW / 2, top + ringB.base);
                lt.p.setColor(g.charging ? col(OK, k) : sub);
                c.drawText(g.label, cx - g.labW / 2, top + ringB.lh + .04f * R + lt.base, lt.p);
                x += ringSize + ringMr;
            }
            float ry = eY + (eH - rangeH) / 2;
            rSmall.p.setColor(sub);
            c.drawText("总续航", rangeX, ry + rSmall.base, rSmall.p);
            ry += rSmall.lh;
            rangeB.color(text);
            rangeB.draw(c, eRange, rangeX, ry + rangeB.base);
            rKm.p.setColor(sub);
            c.drawText("km", rangeX + eRangeW + .06f * R, ry + rangeB.base, rKm.p);
            ry += rangeB.lh;
            rSub.p.setColor(sub);
            for (int i = 0; i < subL.size(); i++) { c.drawText(subL.get(i), rangeX, ry + rSub.base, rSub.p); ry += rSub.lh; }
            if (!chgL.isEmpty()) {
                ry += .04f * R;
                rChg.p.setColor(col(OK, k));
                for (int i = 0; i < chgL.size(); i++) { c.drawText(chgL.get(i), rangeX, ry + rChg.base, rChg.p); ry += rChg.lh; }
            }
            // 状态小卡
            fillC.setColor(panel);
            for (TileV t : tiles) {
                if (!t.shown) continue;
                rr.set(t.x, t.y, t.x + t.w, t.y + t.h);
                c.drawRoundRect(rr, .22f * R, .22f * R, fillC);
            }
            for (TileV t : tiles) {
                if (!t.shown) continue;
                float bx = t.x + .16f * R, by = t.y + (t.h - .56f * R) / 2;
                fill.setColor(t.bg.get(icBg(t.st, k), now));
                rr.set(bx, by, bx + .56f * R, by + .56f * R);
                c.drawRoundRect(rr, .16f * R, .16f * R, fill);
                ic.setColor(t.fg.get(icFg(t.st, k), now));
                drawIcon(c, t.icon, bx + .13f * R, by + .13f * R, .3f * R);
                anim |= t.bg.moving(now) | t.fg.moving(now);
                float tx = bx + .7f * R, ty = t.y + (t.h - (tS.lh + tB.lh + (t.sub.isEmpty() ? 0 : tE.lh))) / 2;
                tS.p.setColor(sub);
                c.drawText(t.d.name, tx, ty + tS.base, tS.p);
                ty += tS.lh;
                tB.p.setColor(t.st == ST_WARN ? col(WARN, k) : t.st == ST_BAD ? col(BAD, k) : text);
                c.drawText(t.vE, tx, ty + tB.base, tB.p);
                ty += tB.lh;
                if (!t.sub.isEmpty()) { tE.p.setColor(sub); c.drawText(t.subE, tx, ty + tE.base, tE.p); }
            }
            // 详细数据
            int line = col(LINE, k);
            secT.p.setColor(sub);
            c.drawText("详细数据", .04f * R, secY + secT.base, secT.p);
            fill.setColor(line);
            float ly = secY + secT.lh / 2;
            c.drawRect(.16f * R + secTW, ly - dp / 2, W - .04f * R, ly + dp / 2, fill);
            for (GrpV g : groups) { rr.set(g.x, g.y, g.x + g.w, g.y + g.h); c.drawRoundRect(rr, .22f * R, .22f * R, fillC); }
            int accent = col(ACCENT, k), okC = col(OK, k), badC = col(BAD, k);
            labT.p.setColor(sub);
            for (GrpV g : groups) {
                float gx = g.x + .18f * R, gr = g.x + g.w - .18f * R, top = g.y + .14f * R;
                ic.setColor(accent);
                drawIcon(c, g.icon, gx, top + (h3H - .3f * R) / 2, .3f * R);
                h3T.p.setColor(text);
                c.drawText(g.d.name, gx + .4f * R, top + (h3H - h3T.lh) / 2 + h3T.base, h3T.p);
                for (RowV r : g.rows) {
                    float rt = g.y + r.y;
                    fill.setColor(line);
                    c.drawRect(gx, rt, gr, rt + dp, fill);
                    float base = rt + dp + .07f * R + labT.base;
                    c.drawText(r.d.label, gx, base, labT.p);
                    valT.p.setColor(r.cls == 1 ? okC : r.cls == 2 ? badC : text);
                    for (int i = 0; i < r.lines.size(); i++) {
                        float bx = gr - r.lw[i], by = base + i * labT.lh;
                        if (i == 0 && r.amb && sw != 0) { fill.setColor(sw); c.drawCircle(bx - .18f * R, by - .08f * R, .1f * R, fill); }
                        c.drawText(r.lines.get(i), bx, by, valT.p);
                    }
                }
            }
            footT.p.setColor(sub);
            c.drawText(FOOT, .06f * R, footY + .02f * R + footT.base, footT.p);
            if (anim) postInvalidateOnAnimation();
        }
    }
}
