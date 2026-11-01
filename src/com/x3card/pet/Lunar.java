package com.x3card.pet;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** 农历 / 节气 / 节日 / 干支（1900–2100），同网页版 _shared/lunar.js（数据表照搬，那边重新生成了这里跟着换）。
 *  日期一律按 Date.UTC 的规矩换算：月、日超出范围顺延（2 月 30 日 = 3 月 2 日），0～99 年当成 1900～1999 年。 */
public final class Lunar {
  private Lunar() {}

  public static final int MIN_YEAR = 1900, MAX_YEAR = 2100;
  // 每个农历年一个数：bit16 闰月是否 30 天；bit15..bit4 正月..腊月是否 30 天；bit3..bit0 闰几月（0 = 无闰月）
  private static final int[] INFO = {0x4bd8,0x4ae0,0xa570,0x54d5,0xd260,0xd950,0x16554,0x56a0,0x9ad0,0x55d2,0x4ae0,0xa5b6,0xa4d0,0xd250,0x1d295,0xb550,0x56a0,0xada2,0x95b0,0x14977,0x49b0,0xa4b0,0xb4b5,0x6a50,0x6d40,0x1ab54,0x2b60,0x9570,0x52f2,0x4970,0x6566,0xd4a0,0xea50,0x16a95,0x5ad0,0x2b60,0x186e3,0x92e0,0x1c8d7,0xc950,0xd4a0,0x1d8a6,0xb550,0x56a0,0x1a5b4,0x25d0,0x92d0,0xd2b2,0xa950,0xb557,0x6ca0,0xb550,0x15355,0x4da0,0xa5b0,0x14573,0x52b0,0xa9a8,0xe950,0x6aa0,0xaea6,0xab50,0x4b60,0xaae4,0xa570,0x5260,0xf263,0xd950,0x5b57,0x56a0,0x96d0,0x4dd5,0x4ad0,0xa4d0,0xd4d4,0xd250,0xd558,0xb540,0xb6a0,0x195a6,0x95b0,0x49b0,0xa974,0xa4b0,0xb27a,0x6a50,0x6d40,0xaf46,0xab60,0x9570,0x4af5,0x4970,0x64b0,0x74a3,0xea50,0x6b58,0x5ac0,0xab60,0x96d5,0x92e0,0xc960,0xd954,0xd4a0,0xda50,0x7552,0x56a0,0xabb7,0x25d0,0x92d0,0xcab5,0xa950,0xb4a0,0xbaa4,0xad50,0x55d9,0x4ba0,0xa5b0,0x15176,0x52b0,0xa930,0x7954,0x6aa0,0xad50,0x5b52,0x4b60,0xa6e6,0xa4e0,0xd260,0xea65,0xd530,0x5aa0,0x76a3,0x96d0,0x4afb,0x4ad0,0xa4d0,0x1d0b6,0xd250,0xd520,0xdd45,0xb5a0,0x56d0,0x55b2,0x49b0,0xa577,0xa4b0,0xaa50,0x1b255,0x6d20,0xada0,0x14b63,0x9370,0x49f8,0x4970,0x64b0,0x168a6,0xea50,0x6aa0,0x1a6c4,0xaae0,0x92e0,0xd2e3,0xc960,0xd557,0xd4a0,0xda50,0x5d55,0x56a0,0xa6d0,0x55d4,0x52d0,0xa9b8,0xa950,0xb4a0,0xb6a6,0xad50,0x55a0,0xaba4,0xa5b0,0x52b0,0xb273,0x6930,0x7337,0x6aa0,0xad50,0x14b55,0x4b60,0xa570,0x54e4,0xd160,0xe968,0xd520,0xdaa0,0x16aa6,0x56d0,0x4ae0,0xa9d4,0xa2d0,0xd150,0xf252,0xd520};
  // 二十四节气（小寒起）每年的日期 = TERM_BASE[k] + TERMS 中对应的一位数字
  private static final int[] TERM_BASE = {4,19,3,18,4,19,4,19,4,20,4,20,6,22,6,22,6,22,7,22,6,21,6,21};
  private static final String TERMS = "211122112122112121222211221122122222212222222221222122222232222222222222222233223232223232222222322222112122112121222211221122122222212222222221222122222222222222222222222233223232223232222222322222112122112121222211221122122222212222222221222122222222222222222222222233223232223232222222322222112122112121222111211122122222212221222221221122122222222222222222222223222232222232222222222222112122112121122111211122122122212221222221221122122222222222222221222223222232222232222222222222112122112121122111211122112122212221222211221122122222222222222221222122222232222232222222222222112122112121122111211122112122212221222211221122122222212222222221222122222232222232222222222222112122112121111111211122112122112121222211221122122222212222222221222122222232222222222222222222112121112121111111211122112122112121222211221122122222212222222221222122222222222222222222222222112121112121111111211122112122112121222211221122122222212222222221222122222222222222222222222222112121112121111111211122112122112121222211221122122222212222222221222122222222222222222222222222111121112121111111211122112122112121122111211122122222212221222221221122122222222222222222222212111121111121111111111122112122112121122111211122112122212221222221221122122222222222222221222212111121111121111111111122112122112121122111211122112122212221222211221122122222212222222221222211111121111121111111111122112122112121122111211122112122112121222211221122122222212222222221222111111121111111111111111122112121112121111111211122112122112121222211221122122222212222222221222111111121111111111111111122112121112121111111211122112122112121222211221122122222212222222221222111111111111111111111111122112121112121111111211122112122112121222211221122122222212222222221222111111111111111111111111122111121112121111111211122112122112121222211221122122222212221222221222111011111111111111111111122111121111121111111211122112122112121122211211122122122212221222221222111011111111111111111111122111121111121111111211122112122112121122111211122112122212221222221221111011111101111111111111112111121111121111111111122112122112121122111211122112122112221222211221111011111101111111110111111111121111111111111111122112121112121122111211122112122112121222211221111011111101111111110111011111121111111111111111122112121112121111111211122112122112121222211221111011111101111111110111011111111111111111111111122112121112121111111211122112122112121222211221111011111101111111110111011111111111111111111111122112121112121111111211122112122112121222211221111011111101111111110111011111111111111111111111122111121111121111111211122112122112121222211221111011111101110111110111011011111111111111111111122111121111121111111211122112122112121122211221111011011101110111110111011011111101111111111111122111121111121111111211122112122112121122111211111001011101110111110110011011111101111111111111112111121111121111111111122112121112121122111211111001011001010111110110011011111101111111110111111111121111111111111111122112121112121122111211111001011001010111100110011011111101111111110111011111111111111111111111122112121112121111111211111001011001010111100110011011111101111111110111011111111111111111111111122112121112121111111211111001011001010111100110011011111101111111110111011111111111111111111111122111121111121111111211111001011001010111100110011011111101111111110111011111111111111111111111122111121111121111111211111001011001010111100110011011011101110111110111011011111111111111111111122111121111121111111211111001011001010011100110011001011101110111110111011011111101111111111111122111121111121111111211111001010001010011000100011001011001010111110110011011111101111111111111111111121111111111111111111001010001010011000100011001011001010111110110011011111101111111111111111111111111111111111111111001010001010011000100011001011001010111100110011011111101111111110111111111111111111111111111111001010001010000000100011001011001010111100110011011111101111111110111011111111111111111111111111001010001010000000100011001011001010111100110011011111101111111110111011111111111111111111111111000010000010000000100011001011001010111100110011011011101110111110111011111111111111111111111111000010000010000000100011001011001010011100110011011011101110111110111011011111101111111111111111000010000010000000100011001011001010011100110011001011001110111110111011011111101111111111111111000010000010000000100011001010001010011000110011001011001010111110110011011111101111111111111100000010000000000000000011001010001010011000100011001011001010111110110011011111101111111111111100000000000000000000000011001010001010011000100011001011001010111100110011011111101111111110111100000000000000000000000011001010001010000000100011001011001010111100110011011111101111111110111000000000000000000000000011001010000010000000100011001011001010111100110011011111101111111110111011111111111111111111";
  private static final int[] TERM_MONTH = {1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12};
  public static final String[] TERM_NAMES = {"小寒", "大寒", "立春", "雨水", "惊蛰", "春分", "清明", "谷雨", "立夏", "小满", "芒种", "夏至",
    "小暑", "大暑", "立秋", "处暑", "白露", "秋分", "寒露", "霜降", "立冬", "小雪", "大雪", "冬至"};
  public static final String[] MONTH_NAMES = {"正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊"};
  public static final String[] DAY_NAMES = {"初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
    "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
    "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"};
  private static final String STEMS = "甲乙丙丁戊己庚辛壬癸", BRANCHES = "子丑寅卯辰巳午未申酉戌亥", ZODIAC = "鼠牛虎兔龙蛇马羊猴鸡狗猪";
  // 节日：键是 月 * 100 + 日
  private static final int[] SOLAR_FEST_KEY = {101, 214, 308, 312, 401, 501, 504, 601, 701, 801, 910, 1001, 1224, 1225};
  private static final String[] SOLAR_FEST = {"元旦", "情人节", "妇女节", "植树节", "愚人节", "劳动节", "青年节",
    "儿童节", "建党节", "建军节", "教师节", "国庆节", "平安夜", "圣诞节"};
  private static final int[] LUNAR_FEST_KEY = {101, 115, 202, 505, 707, 715, 815, 909, 1208, 1223};
  private static final String[] LUNAR_FEST = {"春节", "元宵节", "龙抬头", "端午节", "七夕", "中元节", "中秋节", "重阳节", "腊八节", "小年"};
  private static final int BASE = epochDay(1900, 1, 31); // 农历 1900 年正月初一

  public static final String FUTURE = "future", TODAY = "today", PAST = "past";

  /** 公历日期 */
  public static final class Ymd {
    public final int y, m, d;
    public Ymd(int y, int m, int d) { this.y = y; this.m = m; this.d = d; }
  }

  /** 农历日期（fromSolar 的结果） */
  public static final class Day {
    public final int year, month, day;
    public final boolean leap;          // 闰月
    public final String monthName;      // 如"闰四月""腊月"
    public final String dayName;        // 如"初一""廿九"
    public final int monthDays;         // 这个月 29 还是 30 天
    public final String yearGanzhi, zodiac;
    Day(int year, int month, int day, boolean leap, int monthDays) {
      this.year = year; this.month = month; this.day = day; this.leap = leap; this.monthDays = monthDays;
      monthName = (leap ? "闰" : "") + MONTH_NAMES[month - 1] + "月";
      dayName = DAY_NAMES[day - 1];
      yearGanzhi = ganzhi(year - 4);
      zodiac = String.valueOf(ZODIAC.charAt(((year - 4) % 12 + 12) % 12));
    }
  }

  /** 节气；days 只有 nextTerm 给（离那天还有几天） */
  public static final class Term {
    public final String name;
    public final int y, m, d, days;
    Term(String name, int y, int m, int d, int days) { this.name = name; this.y = y; this.m = m; this.d = d; this.days = days; }
  }

  /** 倒数日 / 纪念日的目标日期：days = 目标 - 今天（过去为负），state 是 FUTURE / TODAY / PAST */
  public static final class Countdown {
    public final int y, m, d, days;
    public final String state;
    Countdown(int y, int m, int d, int days) {
      this.y = y; this.m = m; this.d = d; this.days = days;
      state = days > 0 ? FUTURE : days == 0 ? TODAY : PAST;
    }
  }

  // ---------- 公历日期 ↔ 1970-01-01 起的天数（同 Date.UTC / getUTC*） ----------

  /** Date.UTC(y, m - 1, d) 换成天数：月、日超出范围顺延，0～99 年当成 1900～1999 年 */
  public static int epochDay(int y, int m, int d) {
    if (y >= 0 && y <= 99) y += 1900;
    int mm = m - 1;
    y += Math.floorDiv(mm, 12);
    return civil(y, Math.floorMod(mm, 12) + 1) + d - 1;
  }

  /** 天数换回公历日期 */
  public static Ymd ofEpochDay(int z) {
    z += 719468;
    int era = Math.floorDiv(z, 146097), doe = z - era * 146097;
    int yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    int doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    int mp = (5 * doy + 2) / 153;
    int d = doy - (153 * mp + 2) / 5 + 1, m = mp < 10 ? mp + 3 : mp - 9;
    return new Ymd(yoe + era * 400 + (m <= 2 ? 1 : 0), m, d);
  }

  /** 某年某月 1 日的天数（月份 1～12） */
  private static int civil(int y, int m) {
    if (m <= 2) y--;
    int era = Math.floorDiv(y, 400), yoe = y - era * 400;
    int doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5;
    return era * 146097 + yoe * 365 + yoe / 4 - yoe / 100 + doy - 719468;
  }

  /** 星期几（0 = 周日） */
  public static int weekday(int y, int m, int d) { return Math.floorMod(epochDay(y, m, d) + 4, 7); }

  // ---------- 农历 ----------

  private static int info(int y) { return INFO[y - MIN_YEAR]; }
  private static int leapMonth0(int y) { return info(y) & 0xf; }
  private static int leapDays(int y) { return leapMonth0(y) != 0 ? ((info(y) & 0x10000) != 0 ? 30 : 29) : 0; }
  private static int monthDays0(int y, int m) { return (info(y) & (0x10000 >> m)) != 0 ? 30 : 29; }
  private static int yearDays(int y) {
    int s = 348, v = info(y);
    for (int b = 0x8000; b > 0x8; b >>= 1) if ((v & b) != 0) s++;
    return s + leapDays(y);
  }
  private static boolean inRange(int y) { return y >= MIN_YEAR && y <= MAX_YEAR; }

  private static String ganzhi(int i) {
    i = ((i % 60) + 60) % 60;
    return "" + STEMS.charAt(i % 10) + BRANCHES.charAt(i % 12);
  }

  /** 公历 → 农历；超出 1900-01-31 ~ 2100 年底范围返回 null */
  public static Day fromSolar(int y, int m, int d) {
    int off = epochDay(y, m, d) - BASE;
    if (off < 0) return null;
    int ly = MIN_YEAR, n;
    while (ly <= MAX_YEAR && off >= (n = yearDays(ly))) { off -= n; ly++; }
    if (ly > MAX_YEAR) return null;
    int leap = leapMonth0(ly), lm = 1;
    boolean isLeap = false;
    for (;;) {
      n = isLeap ? leapDays(ly) : monthDays0(ly, lm);
      if (off < n) break;
      off -= n;
      if (!isLeap && lm == leap) isLeap = true;
      else { isLeap = false; lm++; }
    }
    return new Day(ly, lm, off + 1, isLeap, isLeap ? leapDays(ly) : monthDays0(ly, lm));
  }

  /** 农历 → 公历；该月没有三十时取廿九；无此闰月或越界返回 null */
  public static Ymd toSolar(int ly, int lm, int ld, boolean isLeap) {
    if (!inRange(ly) || lm < 1 || lm > 12) return null;
    if (isLeap && leapMonth0(ly) != lm) return null;
    int off = 0, y, m;
    for (y = MIN_YEAR; y < ly; y++) off += yearDays(y);
    int leap = leapMonth0(ly);
    for (m = 1; m < lm; m++) { off += monthDays0(ly, m); if (m == leap) off += leapDays(ly); }
    if (isLeap) off += monthDays0(ly, lm);
    int max = isLeap ? leapDays(ly) : monthDays0(ly, lm);
    off += Math.min(ld, max) - 1;
    return ofEpochDay(BASE + off);
  }

  /** 这一年闰几月（0 = 不闰） */
  public static int leapMonth(int y) { return inRange(y) ? leapMonth0(y) : 0; }

  /** 农历某月（或闰月）的天数；超出范围按 30 */
  public static int monthDays(int y, int m, boolean leap) { return inRange(y) ? (leap ? leapDays(y) : monthDays0(y, m)) : 30; }

  // ---------- 节气 ----------

  private static int termDay(int y, int k) { return inRange(y) ? TERM_BASE[k] + (TERMS.charAt((y - MIN_YEAR) * 24 + k) - '0') : 0; }

  /** 当天的节气名，没有返回 "" */
  public static String term(int y, int m, int d) {
    if (!inRange(y) || m < 1 || m > 12) return "";
    int k = (m - 1) * 2;
    if (termDay(y, k) == d) return TERM_NAMES[k];
    if (termDay(y, k + 1) == d) return TERM_NAMES[k + 1];
    return "";
  }

  /** 这一年的 24 个节气（小寒起） */
  public static List<Term> termsOfYear(int y) {
    List<Term> out = new ArrayList<>();
    if (!inRange(y)) return out;
    for (int k = 0; k < 24; k++) out.add(new Term(TERM_NAMES[k], y, TERM_MONTH[k], termDay(y, k), 0));
    return out;
  }

  /** 今天（含）之后的第一个节气，带相隔天数 */
  public static Term nextTerm(int y, int m, int d) {
    int t0 = epochDay(y, m, d);
    for (int yy = y; yy <= y + 1 && inRange(yy); yy++) {
      for (int k = 0; k < 24; k++) {
        int td = termDay(yy, k), t = epochDay(yy, TERM_MONTH[k], td);
        if (t >= t0) return new Term(TERM_NAMES[k], yy, TERM_MONTH[k], td, t - t0);
      }
    }
    return null;
  }

  // ---------- 节日 ----------

  /** 某月第 n 个星期几（0 = 周日）是几号 */
  private static int nthWeekday(int y, int m, int n, int wd) {
    int first = weekday(y, m, 1);
    return 1 + ((wd - first + 7) % 7) + (n - 1) * 7;
  }

  private static String find(int[] keys, String[] names, int m, int d) {
    if (m < 1 || m > 12 || d < 1 || d > 31) return null;
    int k = m * 100 + d;
    for (int i = 0; i < keys.length; i++) if (keys[i] == k) return names[i];
    return null;
  }

  /** 当天所有节日（农历节日在前），没有返回空表 */
  public static List<String> festivals(int y, int m, int d) {
    List<String> out = new ArrayList<>();
    Day l = fromSolar(y, m, d);
    if (l != null && !l.leap) {
      String f = find(LUNAR_FEST_KEY, LUNAR_FEST, l.month, l.day);
      if (f != null) out.add(f);
      if (l.month == 12 && l.day == l.monthDays) out.add("除夕");
    }
    String s = find(SOLAR_FEST_KEY, SOLAR_FEST, m, d);
    if (s != null) out.add(s);
    if (m == 5 && d == nthWeekday(y, 5, 2, 0)) out.add("母亲节");
    if (m == 6 && d == nthWeekday(y, 6, 3, 0)) out.add("父亲节");
    return out;
  }

  /** 当天的第一个节日，没有返回 "" */
  public static String festival(int y, int m, int d) {
    List<String> f = festivals(y, m, d);
    return f.isEmpty() ? "" : f.get(0);
  }

  // ---------- 干支 ----------

  /** 日干支（以 2000-01-01 戊午日为基准） */
  public static String dayGanzhi(int y, int m, int d) { return ganzhi(54 + epochDay(y, m, d) - epochDay(2000, 1, 1)); }

  /** 月干支（按"节"分月：立春起寅月），年干支按立春分年 */
  public static String monthGanzhi(int y, int m, int d) {
    if (!inRange(y)) return "";
    int t = epochDay(y, m, d), k = -1;
    for (int i = 22; i >= 0; i -= 2) { if (epochDay(y, TERM_MONTH[i], termDay(y, i)) <= t) { k = i; break; } }
    int branch = k < 0 ? 0 : (k / 2 + 1) % 12;              // 小寒前属上一年的子月
    int yy = t < epochDay(y, 2, termDay(y, 2)) ? y - 1 : y;  // 立春前算上一年
    int stem = ((((yy - 4) % 10) + 10) % 10) % 5 * 2 + 2;    // 寅月天干：甲己之年丙作首
    return "" + STEMS.charAt((stem + (branch - 2 + 12) % 12) % 10) + BRANCHES.charAt(branch);
  }

  // ---------- 倒数日 ----------

  /** 每年重复的日子下一次出现的公历日期（含 from 当天）；公历 2 月 29 日平年取 28 日，农历没有三十取廿九 */
  public static Ymd nextOccurrence(boolean lunar, int m, int d, int fy, int fm, int fd) {
    int t0 = epochDay(fy, fm, fd);
    for (int y = fy - 1; y <= fy + 1; y++) {
      Ymd s = lunar ? toSolar(y, m, d, false) : (m == 2 && d == 29 && !isLeapYear(y) ? new Ymd(y, 2, 28) : new Ymd(y, m, d));
      if (s != null && epochDay(s.y, s.m, s.d) >= t0) return s;
    }
    return null;
  }

  public static boolean isLeapYear(int y) { return (y % 4 == 0 && y % 100 != 0) || y % 400 == 0; }

  /** 倒数日 / 纪念日（repeat 时忽略 y）；日期无效返回 null */
  public static Countdown eventDays(boolean lunar, boolean repeat, int y, int m, int d, int ty, int tm, int td) {
    Ymd target;
    if (repeat) target = nextOccurrence(lunar, m, d, ty, tm, td);
    else target = lunar ? toSolar(y, m, d, false) : new Ymd(y, m, d);
    if (target == null) return null;
    return new Countdown(target.y, target.m, target.d, daysBetween(ty, tm, td, target.y, target.m, target.d));
  }

  /** b - a 相差几天 */
  public static int daysBetween(int ay, int am, int ad, int by, int bm, int bd) { return epochDay(by, bm, bd) - epochDay(ay, am, ad); }

  public static int daysBetween(Ymd a, Ymd b) { return daysBetween(a.y, a.m, a.d, b.y, b.m, b.d); }

  /** 本地时间的今天 */
  public static Ymd today() {
    Calendar c = Calendar.getInstance();
    return new Ymd(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
  }
}
