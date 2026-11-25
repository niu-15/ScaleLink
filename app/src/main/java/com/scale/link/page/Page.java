package com.scale.link.page;

import android.view.View;

/** 页面统一接口：构建一次视图，之后用 refresh() 更新数据。 */
public interface Page {
    View build();
    void refresh();
}
