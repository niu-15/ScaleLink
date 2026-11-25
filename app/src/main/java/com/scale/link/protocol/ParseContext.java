package com.scale.link.protocol;

import com.scale.link.model.UserProfile;

/** 协议解析上下文 */
public class ParseContext {
    public UserProfile user;
    public boolean metric = true;   // true=kg, false=lb

    public ParseContext(UserProfile user) {
        this.user = user;
    }
}
