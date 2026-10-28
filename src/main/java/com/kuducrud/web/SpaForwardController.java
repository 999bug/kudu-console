package com.kuducrud.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA 回退：前端用 history 路由，刷新 /tables 之类的地址时返回 index.html。
 */
@Controller
public class SpaForwardController {

    @GetMapping({"/", "/cluster", "/tables", "/table", "/jobs", "/clusters", "/settings"})
    public String index() {
        return "forward:/index.html";
    }
}
