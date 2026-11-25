package com.scale.link.model;

/**
 * 用户档案（体脂秤 BIA 计算需要身高/年龄/性别）
 */
public class UserProfile {
    public int id;
    public String name;
    public int gender;      // 1=男 0=女
    public int age;
    public double height;   // cm
    public double targetWeight; // 目标体重 kg
    /** 头像表情，空串表示用名字首字当头像 */
    public String avatar = "";
    /**
     * 体成分校准偏移（百分点）。不同厂商体脂算法口径不同，
     * 用户拿官方 App 读数校一次后，本地估算结果整体加上这个偏移，从此对齐。
     * 不落库，运行时由 MainActivity 从 SharedPreferences 填入。
     */
    public double fatOffset;

    public UserProfile() {}

    public UserProfile(String name, int gender, int age, double height, double targetWeight) {
        this.name = name;
        this.gender = gender;
        this.age = age;
        this.height = height;
        this.targetWeight = targetWeight;
    }
}
