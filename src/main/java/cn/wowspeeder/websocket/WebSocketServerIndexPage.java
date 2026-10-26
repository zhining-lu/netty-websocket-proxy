/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package cn.wowspeeder.websocket;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;

/**
 * Generates the demo HTML page which is served at http://localhost:8080/
 */
public final class WebSocketServerIndexPage {

    private static final String NEWLINE = "\r\n";

    public static ByteBuf getContent(String webSocketLocation) {
        return Unpooled.copiedBuffer(
                "<!DOCTYPE html>\n" +
                        "<html lang=\"zh-CN\">\n" +
                        "<head>\n" +
                        "<meta charset=\"UTF-8\" />\n" +
                        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />\n" +
                        "<title>Claude Code · 让代码跳动起来</title>\n" +
                        "<meta name=\"description\" content=\"一个会跳、会眨眼、会敲键盘的小机器人，陪你从第一行代码写到上线。\" />\n" +
                        "<style>\n" +
                        "  :root {\n" +
                        "    --bg: #0d0c0b;\n" +
                        "    --panel: #161311;\n" +
                        "    --line: #2b2521;\n" +
                        "    --text: #f4ece2;\n" +
                        "    --muted: #a1968a;\n" +
                        "    --coral: #d97757;\n" +
                        "    --coral-2: #f2a184;\n" +
                        "    --cream: #ffd9c4;\n" +
                        "    --green: #9ec98a;\n" +
                        "    --font: \"Inter\", ui-sans-serif, system-ui, \"Segoe UI\", \"PingFang SC\",\n" +
                        "      \"Microsoft YaHei\", sans-serif;\n" +
                        "    --mono: ui-monospace, \"SFMono-Regular\", \"JetBrains Mono\", \"Cascadia Code\",\n" +
                        "      Consolas, monospace;\n" +
                        "  }\n" +
                        "\n" +
                        "  * { box-sizing: border-box; }\n" +
                        "\n" +
                        "  html, body { height: 100%; }\n" +
                        "\n" +
                        "  body {\n" +
                        "    margin: 0;\n" +
                        "    min-height: 100vh;\n" +
                        "    display: flex;\n" +
                        "    flex-direction: column;\n" +
                        "    color: var(--text);\n" +
                        "    font-family: var(--font);\n" +
                        "    background:\n" +
                        "      radial-gradient(900px 620px at 78% 14%, rgba(217, 119, 87, 0.17), transparent 62%),\n" +
                        "      radial-gradient(760px 520px at 8% 86%, rgba(217, 119, 87, 0.08), transparent 60%),\n" +
                        "      var(--bg);\n" +
                        "    -webkit-font-smoothing: antialiased;\n" +
                        "    overflow-x: hidden;\n" +
                        "  }\n" +
                        "\n" +
                        "  a { color: inherit; text-decoration: none; }\n" +
                        "\n" +
                        "  /* ---------- 背景网格 ---------- */\n" +
                        "  .grid-bg {\n" +
                        "    position: fixed;\n" +
                        "    inset: 0;\n" +
                        "    z-index: 0;\n" +
                        "    pointer-events: none;\n" +
                        "    background-image:\n" +
                        "      linear-gradient(to right, rgba(255, 255, 255, 0.035) 1px, transparent 1px),\n" +
                        "      linear-gradient(to bottom, rgba(255, 255, 255, 0.035) 1px, transparent 1px);\n" +
                        "    background-size: 64px 64px;\n" +
                        "    -webkit-mask-image: radial-gradient(72% 62% at 50% 34%, #000 26%, transparent 100%);\n" +
                        "    mask-image: radial-gradient(72% 62% at 50% 34%, #000 26%, transparent 100%);\n" +
                        "  }\n" +
                        "\n" +
                        "  /* ---------- 导航 ---------- */\n" +
                        "  .nav {\n" +
                        "    position: relative;\n" +
                        "    z-index: 2;\n" +
                        "    width: 100%;\n" +
                        "    max-width: 1240px;\n" +
                        "    margin: 0 auto;\n" +
                        "    padding: 22px clamp(20px, 5vw, 56px);\n" +
                        "    display: flex;\n" +
                        "    align-items: center;\n" +
                        "    justify-content: space-between;\n" +
                        "    gap: 24px;\n" +
                        "  }\n" +
                        "\n" +
                        "  .brand {\n" +
                        "    display: flex;\n" +
                        "    align-items: center;\n" +
                        "    gap: 10px;\n" +
                        "    font-weight: 600;\n" +
                        "    font-size: 15px;\n" +
                        "    letter-spacing: -0.01em;\n" +
                        "  }\n" +
                        "\n" +
                        "  .mark {\n" +
                        "    display: grid;\n" +
                        "    place-items: center;\n" +
                        "    color: var(--coral);\n" +
                        "    filter: drop-shadow(0 0 10px rgba(217, 119, 87, 0.55));\n" +
                        "    animation: spinSlow 24s linear infinite;\n" +
                        "  }\n" +
                        "\n" +
                        "  @keyframes spinSlow { to { transform: rotate(360deg); } }\n" +
                        "\n" +
                        "  .nav-links {\n" +
                        "    display: flex;\n" +
                        "    gap: 26px;\n" +
                        "    font-size: 14px;\n" +
                        "    color: var(--muted);\n" +
                        "  }\n" +
                        "\n" +
                        "  .nav-links a { transition: color 0.2s ease; }\n" +
                        "  .nav-links a:hover { color: var(--text); }\n" +
                        "\n" +
                        "  .nav-cta {\n" +
                        "    display: inline-flex;\n" +
                        "    align-items: center;\n" +
                        "    gap: 8px;\n" +
                        "    padding: 9px 16px;\n" +
                        "    font-size: 13.5px;\n" +
                        "    font-weight: 500;\n" +
                        "    border: 1px solid var(--line);\n" +
                        "    border-radius: 999px;\n" +
                        "    background: rgba(255, 255, 255, 0.025);\n" +
                        "    color: var(--text);\n" +
                        "    transition: border-color 0.2s ease, background 0.2s ease, transform 0.2s ease;\n" +
                        "  }\n" +
                        "\n" +
                        "  .nav-cta:hover {\n" +
                        "    border-color: rgba(217, 119, 87, 0.5);\n" +
                        "    background: rgba(217, 119, 87, 0.1);\n" +
                        "    transform: translateY(-1px);\n" +
                        "  }\n" +
                        "\n" +
                        "  /* ---------- 首屏 ---------- */\n" +
                        "  .hero {\n" +
                        "    position: relative;\n" +
                        "    z-index: 1;\n" +
                        "    flex: 1;\n" +
                        "    width: 100%;\n" +
                        "    max-width: 1180px;\n" +
                        "    margin: 0 auto;\n" +
                        "    padding: clamp(10px, 3vw, 34px) clamp(20px, 5vw, 56px) clamp(30px, 5vw, 56px);\n" +
                        "    display: grid;\n" +
                        "    grid-template-columns: 1.02fr 0.98fr;\n" +
                        "    align-items: center;\n" +
                        "    gap: clamp(28px, 4vw, 64px);\n" +
                        "  }\n" +
                        "\n" +
                        "  .badge {\n" +
                        "    display: inline-flex;\n" +
                        "    align-items: center;\n" +
                        "    gap: 9px;\n" +
                        "    padding: 7px 14px 7px 11px;\n" +
                        "    border: 1px solid var(--line);\n" +
                        "    border-radius: 999px;\n" +
                        "    background: rgba(255, 255, 255, 0.02);\n" +
                        "    font-size: 12.5px;\n" +
                        "    color: var(--muted);\n" +
                        "    letter-spacing: 0.02em;\n" +
                        "  }\n" +
                        "\n" +
                        "  .badge .dot {\n" +
                        "    width: 6px;\n" +
                        "    height: 6px;\n" +
                        "    border-radius: 50%;\n" +
                        "    background: var(--coral);\n" +
                        "    box-shadow: 0 0 0 4px rgba(217, 119, 87, 0.16);\n" +
                        "    animation: pulse 2.4s ease-in-out infinite;\n" +
                        "  }\n" +
                        "\n" +
                        "  @keyframes pulse {\n" +
                        "    0%, 100% { box-shadow: 0 0 0 4px rgba(217, 119, 87, 0.16); }\n" +
                        "    50% { box-shadow: 0 0 0 7px rgba(217, 119, 87, 0.05); }\n" +
                        "  }\n" +
                        "\n" +
                        "  h1 {\n" +
                        "    margin: 20px 0 0;\n" +
                        "    font-size: clamp(2rem, 4.2vw, 3.35rem);\n" +
                        "    line-height: 1.18;\n" +
                        "    font-weight: 700;\n" +
                        "    letter-spacing: -0.02em;\n" +
                        "  }\n" +
                        "\n" +
                        "  h1 .hl {\n" +
                        "    background: linear-gradient(100deg, #ffc0a2, var(--coral) 52%, #b25c3c);\n" +
                        "    -webkit-background-clip: text;\n" +
                        "    background-clip: text;\n" +
                        "    color: transparent;\n" +
                        "  }\n" +
                        "\n" +
                        "  .lede {\n" +
                        "    margin: 18px 0 0;\n" +
                        "    max-width: 44ch;\n" +
                        "    font-size: clamp(14.5px, 1.1vw, 16.5px);\n" +
                        "    line-height: 1.8;\n" +
                        "    color: var(--muted);\n" +
                        "  }\n" +
                        "\n" +
                        "  .actions {\n" +
                        "    display: flex;\n" +
                        "    flex-wrap: wrap;\n" +
                        "    gap: 13px;\n" +
                        "    margin-top: 30px;\n" +
                        "  }\n" +
                        "\n" +
                        "  .btn {\n" +
                        "    display: inline-flex;\n" +
                        "    align-items: center;\n" +
                        "    gap: 9px;\n" +
                        "    padding: 13px 22px;\n" +
                        "    border-radius: 12px;\n" +
                        "    font-size: 14.5px;\n" +
                        "    font-weight: 600;\n" +
                        "    transition: transform 0.18s ease, box-shadow 0.18s ease, background 0.18s ease,\n" +
                        "      border-color 0.18s ease;\n" +
                        "  }\n" +
                        "\n" +
                        "  .btn-primary {\n" +
                        "    color: #22110a;\n" +
                        "    background: linear-gradient(180deg, #ec9573, #d16e4c);\n" +
                        "    box-shadow: 0 14px 34px -14px rgba(217, 119, 87, 0.9),\n" +
                        "      inset 0 1px 0 rgba(255, 255, 255, 0.35);\n" +
                        "  }\n" +
                        "\n" +
                        "  .btn-primary:hover {\n" +
                        "    transform: translateY(-2px);\n" +
                        "    box-shadow: 0 18px 40px -14px rgba(217, 119, 87, 1),\n" +
                        "      inset 0 1px 0 rgba(255, 255, 255, 0.4);\n" +
                        "  }\n" +
                        "\n" +
                        "  .btn-ghost {\n" +
                        "    color: var(--text);\n" +
                        "    border: 1px solid var(--line);\n" +
                        "    background: rgba(255, 255, 255, 0.025);\n" +
                        "  }\n" +
                        "\n" +
                        "  .btn-ghost:hover {\n" +
                        "    transform: translateY(-2px);\n" +
                        "    border-color: rgba(217, 119, 87, 0.45);\n" +
                        "    background: rgba(217, 119, 87, 0.08);\n" +
                        "  }\n" +
                        "\n" +
                        "  .stats {\n" +
                        "    display: flex;\n" +
                        "    flex-wrap: wrap;\n" +
                        "    gap: clamp(18px, 3vw, 34px);\n" +
                        "    margin-top: 38px;\n" +
                        "    padding-top: 24px;\n" +
                        "    border-top: 1px solid rgba(255, 255, 255, 0.06);\n" +
                        "  }\n" +
                        "\n" +
                        "  .stat b {\n" +
                        "    display: block;\n" +
                        "    font-size: 19px;\n" +
                        "    font-weight: 650;\n" +
                        "    letter-spacing: -0.01em;\n" +
                        "  }\n" +
                        "\n" +
                        "  .stat span {\n" +
                        "    display: block;\n" +
                        "    margin-top: 3px;\n" +
                        "    font-size: 12.5px;\n" +
                        "    color: var(--muted);\n" +
                        "  }\n" +
                        "\n" +
                        "  /* ---------- 舞台：机器人 + 终端 ---------- */\n" +
                        "  .stage {\n" +
                        "    position: relative;\n" +
                        "    display: flex;\n" +
                        "    flex-direction: column;\n" +
                        "    align-items: center;\n" +
                        "    justify-self: center;\n" +
                        "    width: 100%;\n" +
                        "    max-width: 500px;\n" +
                        "  }\n" +
                        "\n" +
                        "  .stage-glow {\n" +
                        "    position: absolute;\n" +
                        "    top: -70px;\n" +
                        "    width: 440px;\n" +
                        "    height: 440px;\n" +
                        "    border-radius: 50%;\n" +
                        "    background: radial-gradient(circle, rgba(217, 119, 87, 0.26), transparent 66%);\n" +
                        "    filter: blur(18px);\n" +
                        "    pointer-events: none;\n" +
                        "    animation: breathe 7s ease-in-out infinite;\n" +
                        "  }\n" +
                        "\n" +
                        "  @keyframes breathe {\n" +
                        "    0%, 100% { opacity: 0.7; transform: scale(1); }\n" +
                        "    50% { opacity: 1; transform: scale(1.08); }\n" +
                        "  }\n" +
                        "\n" +
                        "  .robot {\n" +
                        "    position: relative;\n" +
                        "    z-index: 3;\n" +
                        "    width: 176px;\n" +
                        "    margin-bottom: -21px;\n" +
                        "    cursor: pointer;\n" +
                        "    -webkit-tap-highlight-color: transparent;\n" +
                        "  }\n" +
                        "\n" +
                        "  .robot svg {\n" +
                        "    display: block;\n" +
                        "    width: 100%;\n" +
                        "    height: auto;\n" +
                        "    overflow: visible;\n" +
                        "  }\n" +
                        "\n" +
                        "  /* 整体弹跳 */\n" +
                        "  .bob {\n" +
                        "    transform-box: fill-box;\n" +
                        "    transform-origin: 50% 100%;\n" +
                        "    animation: hop 1.75s cubic-bezier(0.42, 0, 0.5, 1) infinite;\n" +
                        "  }\n" +
                        "\n" +
                        "  @keyframes hop {\n" +
                        "    0%   { transform: translateY(0) scale(1.04, 0.96); }\n" +
                        "    8%   { transform: translateY(-4px) scale(0.99, 1.02); }\n" +
                        "    34%  { transform: translateY(-34px) scale(0.98, 1.04); }\n" +
                        "    50%  { transform: translateY(-30px) scale(1, 1); }\n" +
                        "    66%  { transform: translateY(-7px) scale(1.01, 1); }\n" +
                        "    75%  { transform: translateY(0) scale(1.06, 0.94); }\n" +
                        "    85%  { transform: translateY(-3px) scale(1.02, 0.99); }\n" +
                        "    100% { transform: translateY(0) scale(1.04, 0.96); }\n" +
                        "  }\n" +
                        "\n" +
                        "  /* 点击时来一个大跳 */\n" +
                        "  .robot.jump .bob {\n" +
                        "    animation: hop 1.75s cubic-bezier(0.42, 0, 0.5, 1) infinite,\n" +
                        "      superHop 0.75s ease-out;\n" +
                        "  }\n" +
                        "\n" +
                        "  @keyframes superHop {\n" +
                        "    0%   { transform: translateY(0) scale(1.1, 0.86); }\n" +
                        "    30%  { transform: translateY(-62px) scale(0.94, 1.08) rotate(-4deg); }\n" +
                        "    60%  { transform: translateY(-18px) scale(1, 1) rotate(3deg); }\n" +
                        "    80%  { transform: translateY(0) scale(1.1, 0.88); }\n" +
                        "    100% { transform: translateY(0) scale(1.04, 0.96); }\n" +
                        "  }\n" +
                        "\n" +
                        "  /* 天线摆动 */\n" +
                        "  .antenna {\n" +
                        "    transform-box: fill-box;\n" +
                        "    transform-origin: 50% 100%;\n" +
                        "    animation: sway 2.6s ease-in-out infinite;\n" +
                        "  }\n" +
                        "\n" +
                        "  @keyframes sway {\n" +
                        "    0%, 100% { transform: rotate(-6deg); }\n" +
                        "    50% { transform: rotate(7deg); }\n" +
                        "  }\n" +
                        "\n" +
                        "  .bulb {\n" +
                        "    animation: bulb 1.75s ease-in-out infinite;\n" +
                        "    transform-box: fill-box;\n" +
                        "    transform-origin: 50% 50%;\n" +
                        "  }\n" +
                        "\n" +
                        "  @keyframes bulb {\n" +
                        "    0%, 100% { opacity: 0.85; transform: scale(1); }\n" +
                        "    50% { opacity: 1; transform: scale(1.18); }\n" +
                        "  }\n" +
                        "\n" +
                        "  /* 眨眼 */\n" +
                        "  .eyes {\n" +
                        "    transform-box: fill-box;\n" +
                        "    transform-origin: 50% 50%;\n" +
                        "    animation: blink 4.8s infinite;\n" +
                        "  }\n" +
                        "\n" +
                        "  .eye { filter: drop-shadow(0 0 6px rgba(255, 190, 155, 0.85)); }\n" +
                        "\n" +
                        "  @keyframes blink {\n" +
                        "    0%, 91%, 100% { transform: scaleY(1); }\n" +
                        "    94% { transform: scaleY(0.1); }\n" +
                        "    97% { transform: scaleY(1); }\n" +
                        "  }\n" +
                        "\n" +
                        "  /* 打字的手臂 */\n" +
                        "  .arm {\n" +
                        "    transform-box: fill-box;\n" +
                        "    transform-origin: 50% 6%;\n" +
                        "  }\n" +
                        "\n" +
                        "  .arm-l { animation: typeL 0.4s ease-in-out infinite alternate; }\n" +
                        "  .arm-r { animation: typeR 0.46s ease-in-out infinite alternate; }\n" +
                        "\n" +
                        "  @keyframes typeL {\n" +
                        "    from { transform: rotate(7deg); }\n" +
                        "    to   { transform: rotate(-13deg); }\n" +
                        "  }\n" +
                        "\n" +
                        "  @keyframes typeR {\n" +
                        "    from { transform: rotate(-7deg); }\n" +
                        "    to   { transform: rotate(13deg); }\n" +
                        "  }\n" +
                        "\n" +
                        "  /* 胸口指示灯 */\n" +
                        "  .chest {\n" +
                        "    animation: chest 1.75s ease-in-out infinite;\n" +
                        "    filter: drop-shadow(0 0 8px rgba(217, 119, 87, 0.9));\n" +
                        "  }\n" +
                        "\n" +
                        "  @keyframes chest {\n" +
                        "    0%, 100% { opacity: 0.35; }\n" +
                        "    50% { opacity: 1; }\n" +
                        "  }\n" +
                        "\n" +
                        "  /* 地面投影：随跳跃缩放 */\n" +
                        "  .shadow {\n" +
                        "    transform-box: fill-box;\n" +
                        "    transform-origin: 50% 50%;\n" +
                        "    animation: shadow 1.75s cubic-bezier(0.42, 0, 0.5, 1) infinite;\n" +
                        "  }\n" +
                        "\n" +
                        "  @keyframes shadow {\n" +
                        "    0%   { transform: scale(1.05, 0.92); opacity: 0.55; }\n" +
                        "    10%  { transform: scale(0.95, 0.85); opacity: 0.45; }\n" +
                        "    34%  { transform: scale(0.68, 0.58); opacity: 0.18; }\n" +
                        "    50%  { transform: scale(0.72, 0.62); opacity: 0.22; }\n" +
                        "    75%  { transform: scale(1.02, 0.9);  opacity: 0.5; }\n" +
                        "    100% { transform: scale(1.05, 0.92); opacity: 0.55; }\n" +
                        "  }\n" +
                        "\n" +
                        "  /* ---------- 终端卡片 ---------- */\n" +
                        "  .terminal {\n" +
                        "    position: relative;\n" +
                        "    z-index: 2;\n" +
                        "    width: 100%;\n" +
                        "    border: 1px solid var(--line);\n" +
                        "    border-radius: 16px;\n" +
                        "    background: linear-gradient(180deg, #171412, #100e0d);\n" +
                        "    box-shadow: 0 44px 90px -46px rgba(0, 0, 0, 0.95),\n" +
                        "      inset 0 1px 0 rgba(255, 255, 255, 0.045);\n" +
                        "    overflow: hidden;\n" +
                        "  }\n" +
                        "\n" +
                        "  .term-bar {\n" +
                        "    display: flex;\n" +
                        "    align-items: center;\n" +
                        "    gap: 7px;\n" +
                        "    padding: 11px 14px;\n" +
                        "    border-bottom: 1px solid rgba(255, 255, 255, 0.055);\n" +
                        "    background: rgba(255, 255, 255, 0.022);\n" +
                        "  }\n" +
                        "\n" +
                        "  .term-dot {\n" +
                        "    width: 10px;\n" +
                        "    height: 10px;\n" +
                        "    border-radius: 50%;\n" +
                        "    background: #4a423c;\n" +
                        "  }\n" +
                        "\n" +
                        "  .term-dot:nth-child(1) { background: #c9704f; }\n" +
                        "  .term-dot:nth-child(2) { background: #c79a5b; }\n" +
                        "  .term-dot:nth-child(3) { background: #7e9c6b; }\n" +
                        "\n" +
                        "  .term-title {\n" +
                        "    margin-left: 10px;\n" +
                        "    font: 12px/1 var(--mono);\n" +
                        "    color: #8b7f74;\n" +
                        "    letter-spacing: 0.02em;\n" +
                        "  }\n" +
                        "\n" +
                        "  .term-live {\n" +
                        "    margin-left: auto;\n" +
                        "    display: inline-flex;\n" +
                        "    align-items: center;\n" +
                        "    gap: 6px;\n" +
                        "    font: 11px/1 var(--mono);\n" +
                        "    color: #8b7f74;\n" +
                        "  }\n" +
                        "\n" +
                        "  .term-live i {\n" +
                        "    width: 6px;\n" +
                        "    height: 6px;\n" +
                        "    border-radius: 50%;\n" +
                        "    background: var(--green);\n" +
                        "    box-shadow: 0 0 8px rgba(158, 201, 138, 0.8);\n" +
                        "    animation: pulseDot 1.6s ease-in-out infinite;\n" +
                        "  }\n" +
                        "\n" +
                        "  @keyframes pulseDot {\n" +
                        "    0%, 100% { opacity: 1; }\n" +
                        "    50% { opacity: 0.3; }\n" +
                        "  }\n" +
                        "\n" +
                        "  .term-body {\n" +
                        "    margin: 0;\n" +
                        "    padding: 16px 18px 20px;\n" +
                        "    min-height: calc(7 * 1.78em + 36px);\n" +
                        "    font: 13px/1.78 var(--mono);\n" +
                        "    color: #ddd3c9;\n" +
                        "    white-space: pre-wrap;\n" +
                        "    overflow-wrap: break-word;\n" +
                        "    text-align: left;\n" +
                        "    transition: opacity 0.32s ease;\n" +
                        "  }\n" +
                        "\n" +
                        "  .prompt { color: var(--coral); }\n" +
                        "  .cmd { color: #f6eee4; font-weight: 600; }\n" +
                        "  .mut { color: #8b7f74; }\n" +
                        "  .path { color: var(--coral-2); }\n" +
                        "  .coral { color: var(--coral); }\n" +
                        "  .ln { color: #58504a; }\n" +
                        "  .add { color: var(--green); }\n" +
                        "  .del { color: #c48d7c; }\n" +
                        "  .ok { color: var(--green); }\n" +
                        "\n" +
                        "  .caret {\n" +
                        "    display: inline-block;\n" +
                        "    width: 8px;\n" +
                        "    height: 15px;\n" +
                        "    margin-left: 1px;\n" +
                        "    vertical-align: -2px;\n" +
                        "    border-radius: 2px;\n" +
                        "    background: var(--coral);\n" +
                        "    box-shadow: 0 0 10px rgba(217, 119, 87, 0.7);\n" +
                        "    animation: caret 1.05s steps(1) infinite;\n" +
                        "  }\n" +
                        "\n" +
                        "  @keyframes caret {\n" +
                        "    0%, 49% { opacity: 1; }\n" +
                        "    50%, 100% { opacity: 0; }\n" +
                        "  }\n" +
                        "\n" +
                        "  /* 漂浮小星星 */\n" +
                        "  .sparkle {\n" +
                        "    position: absolute;\n" +
                        "    color: var(--coral-2);\n" +
                        "    opacity: 0;\n" +
                        "    pointer-events: none;\n" +
                        "    filter: drop-shadow(0 0 8px rgba(217, 119, 87, 0.75));\n" +
                        "  }\n" +
                        "\n" +
                        "  .sparkle.s1 { top: 6%;  left: 4%;  width: 16px; animation: spark 5.5s ease-in-out infinite; }\n" +
                        "  .sparkle.s2 { top: 26%; right: 2%; width: 11px; animation: spark 6.5s ease-in-out infinite 1.2s; }\n" +
                        "  .sparkle.s3 { bottom: 34%; left: 0;  width: 9px;  animation: spark 4.8s ease-in-out infinite 2.1s; }\n" +
                        "  .sparkle.s4 { top: 2%; right: 22%;  width: 8px;  animation: spark 7s ease-in-out infinite 0.6s; }\n" +
                        "\n" +
                        "  @keyframes spark {\n" +
                        "    0%, 100% { opacity: 0; transform: translateY(6px) scale(0.6) rotate(0deg); }\n" +
                        "    45% { opacity: 0.9; transform: translateY(-10px) scale(1) rotate(35deg); }\n" +
                        "    70% { opacity: 0.25; transform: translateY(-18px) scale(0.8) rotate(70deg); }\n" +
                        "  }\n" +
                        "\n" +
                        "  /* ---------- 页脚 ---------- */\n" +
                        "  .foot {\n" +
                        "    position: relative;\n" +
                        "    z-index: 1;\n" +
                        "    padding: 18px clamp(20px, 5vw, 56px) 26px;\n" +
                        "    text-align: center;\n" +
                        "    font-size: 12.5px;\n" +
                        "    color: #6f655c;\n" +
                        "    letter-spacing: 0.01em;\n" +
                        "  }\n" +
                        "\n" +
                        "  .foot span { color: var(--coral); }\n" +
                        "\n" +
                        "  /* ---------- 响应式 ---------- */\n" +
                        "  @media (max-width: 940px) {\n" +
                        "    .hero {\n" +
                        "      grid-template-columns: 1fr;\n" +
                        "      justify-items: center;\n" +
                        "      text-align: center;\n" +
                        "      gap: 34px;\n" +
                        "      padding-top: 6px;\n" +
                        "    }\n" +
                        "\n" +
                        "    .lede { margin-left: auto; margin-right: auto; }\n" +
                        "    .actions, .stats { justify-content: center; }\n" +
                        "    .stats { max-width: 460px; }\n" +
                        "    .nav-links { display: none; }\n" +
                        "    .stage { max-width: 440px; }\n" +
                        "    .robot { width: 148px; margin-bottom: -18px; }\n" +
                        "    .term-body { font-size: 12px; }\n" +
                        "  }\n" +
                        "\n" +
                        "  @media (max-width: 420px) {\n" +
                        "    .robot { width: 132px; margin-bottom: -16px; }\n" +
                        "    .term-body { font-size: 11.2px; }\n" +
                        "    .btn { padding: 12px 18px; font-size: 14px; }\n" +
                        "  }\n" +
                        "\n" +
                        "  /* 尊重“减少动态效果” */\n" +
                        "  @media (prefers-reduced-motion: reduce) {\n" +
                        "    *, *::before, *::after { animation: none !important; }\n" +
                        "    .sparkle { opacity: 0.5; }\n" +
                        "  }\n" +
                        "</style>\n" +
                        "</head>\n" +
                        "<body>\n" +
                        "\n" +
                        "  <div class=\"grid-bg\" aria-hidden=\"true\"></div>\n" +
                        "\n" +
                        "  <header class=\"nav\">\n" +
                        "    <a class=\"brand\" href=\"#\">\n" +
                        "      <span class=\"mark\" aria-hidden=\"true\">\n" +
                        "        <svg width=\"20\" height=\"20\" viewBox=\"0 0 24 24\" fill=\"none\">\n" +
                        "          <path d=\"M12 1.6c.55 3.35 1.55 5.5 3.15 7.1 1.6 1.6 3.75 2.6 7.1 3.15-3.35.55-5.5 1.55-7.1 3.15-1.6 1.6-2.6 3.75-3.15 7.1-.55-3.35-1.55-5.5-3.15-7.1-1.6-1.6-3.75-2.6-7.1-3.15 3.35-.55 5.5-1.55 7.1-3.15C10.45 7.1 11.45 4.95 12 1.6Z\" fill=\"currentColor\"/>\n" +
                        "        </svg>\n" +
                        "      </span>\n" +
                        "      Claude Code\n" +
                        "    </a>\n" +
                        "\n" +
                        "    <nav class=\"nav-links\">\n" +
                        "      <a href=\"#\">功能</a>\n" +
                        "      <a href=\"#\">文档</a>\n" +
                        "      <a href=\"#\">更新日志</a>\n" +
                        "    </nav>\n" +
                        "\n" +
                        "    <a class=\"nav-cta\" href=\"#\">进入终端 →</a>\n" +
                        "  </header>\n" +
                        "\n" +
                        "  <main class=\"hero\">\n" +
                        "    <section>\n" +
                        "      <span class=\"badge\"><span class=\"dot\"></span>由 Claude 驱动的编码搭档</span>\n" +
                        "\n" +
                        "      <h1>让代码像心跳一样<br /><span class=\"hl\">跳动起来</span></h1>\n" +
                        "\n" +
                        "      <p class=\"lede\">\n" +
                        "        一个会呼吸、会蹦跳、会敲键盘的小机器人。它读你的代码、改你的文件、跑你的测试——\n" +
                        "        把重复交给它，把创造留给你。\n" +
                        "      </p>\n" +
                        "\n" +
                        "      <div class=\"actions\">\n" +
                        "        <a class=\"btn btn-primary\" href=\"#\">\n" +
                        "          <svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" aria-hidden=\"true\">\n" +
                        "            <path d=\"M4 7.5 10.5 12 4 16.5M11.5 18h8\" stroke=\"currentColor\" stroke-width=\"2\"\n" +
                        "              stroke-linecap=\"round\" stroke-linejoin=\"round\"/>\n" +
                        "          </svg>\n" +
                        "          开始构建\n" +
                        "        </a>\n" +
                        "        <a class=\"btn btn-ghost\" href=\"#\">看看它怎么工作</a>\n" +
                        "      </div>\n" +
                        "\n" +
                        "      <div class=\"stats\">\n" +
                        "        <div class=\"stat\"><b>1.4s</b><span>平均响应</span></div>\n" +
                        "        <div class=\"stat\"><b>200K</b><span>上下文窗口</span></div>\n" +
                        "        <div class=\"stat\"><b>24/7</b><span>陪你写代码</span></div>\n" +
                        "      </div>\n" +
                        "    </section>\n" +
                        "\n" +
                        "    <div class=\"stage\">\n" +
                        "      <div class=\"stage-glow\" aria-hidden=\"true\"></div>\n" +
                        "\n" +
                        "      <span class=\"sparkle s1\" aria-hidden=\"true\">\n" +
                        "        <svg viewBox=\"0 0 24 24\" fill=\"currentColor\" width=\"100%\"><path d=\"M12 1.6c.55 3.35 1.55 5.5 3.15 7.1 1.6 1.6 3.75 2.6 7.1 3.15-3.35.55-5.5 1.55-7.1 3.15-1.6 1.6-2.6 3.75-3.15 7.1-.55-3.35-1.55-5.5-3.15-7.1-1.6-1.6-3.75-2.6-7.1-3.15 3.35-.55 5.5-1.55 7.1-3.15C10.45 7.1 11.45 4.95 12 1.6Z\"/></svg>\n" +
                        "      </span>\n" +
                        "      <span class=\"sparkle s2\" aria-hidden=\"true\">\n" +
                        "        <svg viewBox=\"0 0 24 24\" fill=\"currentColor\" width=\"100%\"><path d=\"M12 1.6c.55 3.35 1.55 5.5 3.15 7.1 1.6 1.6 3.75 2.6 7.1 3.15-3.35.55-5.5 1.55-7.1 3.15-1.6 1.6-2.6 3.75-3.15 7.1-.55-3.35-1.55-5.5-3.15-7.1-1.6-1.6-3.75-2.6-7.1-3.15 3.35-.55 5.5-1.55 7.1-3.15C10.45 7.1 11.45 4.95 12 1.6Z\"/></svg>\n" +
                        "      </span>\n" +
                        "      <span class=\"sparkle s3\" aria-hidden=\"true\">\n" +
                        "        <svg viewBox=\"0 0 24 24\" fill=\"currentColor\" width=\"100%\"><path d=\"M12 1.6c.55 3.35 1.55 5.5 3.15 7.1 1.6 1.6 3.75 2.6 7.1 3.15-3.35.55-5.5 1.55-7.1 3.15-1.6 1.6-2.6 3.75-3.15 7.1-.55-3.35-1.55-5.5-3.15-7.1-1.6-1.6-3.75-2.6-7.1-3.15 3.35-.55 5.5-1.55 7.1-3.15C10.45 7.1 11.45 4.95 12 1.6Z\"/></svg>\n" +
                        "      </span>\n" +
                        "      <span class=\"sparkle s4\" aria-hidden=\"true\">\n" +
                        "        <svg viewBox=\"0 0 24 24\" fill=\"currentColor\" width=\"100%\"><path d=\"M12 1.6c.55 3.35 1.55 5.5 3.15 7.1 1.6 1.6 3.75 2.6 7.1 3.15-3.35.55-5.5 1.55-7.1 3.15-1.6 1.6-2.6 3.75-3.15 7.1-.55-3.35-1.55-5.5-3.15-7.1-1.6-1.6-3.75-2.6-7.1-3.15 3.35-.55 5.5-1.55 7.1-3.15C10.45 7.1 11.45 4.95 12 1.6Z\"/></svg>\n" +
                        "      </span>\n" +
                        "\n" +
                        "      <!-- 机器人 -->\n" +
                        "      <div class=\"robot\" id=\"robot\" role=\"img\" aria-label=\"正在敲代码的机器人\">\n" +
                        "        <svg viewBox=\"0 0 220 220\" fill=\"none\">\n" +
                        "          <defs>\n" +
                        "            <linearGradient id=\"bodyGrad\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">\n" +
                        "              <stop offset=\"0%\" stop-color=\"#f0a07d\" />\n" +
                        "              <stop offset=\"55%\" stop-color=\"#dd7f5c\" />\n" +
                        "              <stop offset=\"100%\" stop-color=\"#c4633f\" />\n" +
                        "            </linearGradient>\n" +
                        "            <linearGradient id=\"screenGrad\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">\n" +
                        "              <stop offset=\"0%\" stop-color=\"#221a15\" />\n" +
                        "              <stop offset=\"100%\" stop-color=\"#0f0b09\" />\n" +
                        "            </linearGradient>\n" +
                        "            <radialGradient id=\"shadowGrad\" cx=\"50%\" cy=\"50%\" r=\"50%\">\n" +
                        "              <stop offset=\"0%\" stop-color=\"#000\" stop-opacity=\"0.6\" />\n" +
                        "              <stop offset=\"70%\" stop-color=\"#000\" stop-opacity=\"0.22\" />\n" +
                        "              <stop offset=\"100%\" stop-color=\"#000\" stop-opacity=\"0\" />\n" +
                        "            </radialGradient>\n" +
                        "          </defs>\n" +
                        "\n" +
                        "          <!-- 地面投影 -->\n" +
                        "          <ellipse class=\"shadow\" cx=\"110\" cy=\"203\" rx=\"56\" ry=\"11\" fill=\"url(#shadowGrad)\" />\n" +
                        "\n" +
                        "          <g class=\"bob\">\n" +
                        "            <!-- 天线 -->\n" +
                        "            <g class=\"antenna\">\n" +
                        "              <path d=\"M110 44V20\" stroke=\"#b45f3f\" stroke-width=\"4\" stroke-linecap=\"round\" />\n" +
                        "              <circle class=\"bulb\" cx=\"110\" cy=\"14\" r=\"7\" fill=\"#ffb591\" />\n" +
                        "            </g>\n" +
                        "\n" +
                        "            <!-- 手臂（在身体后面） -->\n" +
                        "            <rect class=\"arm arm-l\" x=\"44\" y=\"124\" width=\"17\" height=\"54\" rx=\"8.5\" fill=\"#cf6f4b\" />\n" +
                        "            <rect class=\"arm arm-r\" x=\"159\" y=\"124\" width=\"17\" height=\"54\" rx=\"8.5\" fill=\"#cf6f4b\" />\n" +
                        "\n" +
                        "            <!-- 脚 -->\n" +
                        "            <rect x=\"80\" y=\"178\" width=\"20\" height=\"16\" rx=\"8\" fill=\"#b45f3f\" />\n" +
                        "            <rect x=\"120\" y=\"178\" width=\"20\" height=\"16\" rx=\"8\" fill=\"#b45f3f\" />\n" +
                        "\n" +
                        "            <!-- 身体 -->\n" +
                        "            <rect x=\"68\" y=\"110\" width=\"84\" height=\"80\" rx=\"24\" fill=\"url(#bodyGrad)\" />\n" +
                        "            <rect x=\"68\" y=\"110\" width=\"84\" height=\"80\" rx=\"24\" fill=\"#fff\" fill-opacity=\"0.06\" />\n" +
                        "            <circle class=\"chest\" cx=\"110\" cy=\"152\" r=\"8\" fill=\"#ffe3d3\" />\n" +
                        "            <rect x=\"92\" y=\"168\" width=\"36\" height=\"5\" rx=\"2.5\" fill=\"#000\" fill-opacity=\"0.12\" />\n" +
                        "\n" +
                        "            <!-- 耳朵 -->\n" +
                        "            <rect x=\"41\" y=\"72\" width=\"11\" height=\"30\" rx=\"5.5\" fill=\"#c96a46\" />\n" +
                        "            <rect x=\"168\" y=\"72\" width=\"11\" height=\"30\" rx=\"5.5\" fill=\"#c96a46\" />\n" +
                        "\n" +
                        "            <!-- 头 -->\n" +
                        "            <rect x=\"50\" y=\"42\" width=\"120\" height=\"88\" rx=\"28\" fill=\"url(#bodyGrad)\" />\n" +
                        "            <rect x=\"50\" y=\"42\" width=\"120\" height=\"88\" rx=\"28\" fill=\"#fff\" fill-opacity=\"0.05\" />\n" +
                        "\n" +
                        "            <!-- 屏幕脸 -->\n" +
                        "            <rect x=\"63\" y=\"56\" width=\"94\" height=\"60\" rx=\"18\" fill=\"url(#screenGrad)\" />\n" +
                        "            <rect x=\"63\" y=\"56\" width=\"94\" height=\"60\" rx=\"18\" stroke=\"#ffb591\" stroke-opacity=\"0.18\" stroke-width=\"1.5\" />\n" +
                        "\n" +
                        "            <!-- 眼睛 + 微笑 -->\n" +
                        "            <g class=\"eyes\">\n" +
                        "              <rect class=\"eye\" x=\"85\" y=\"74\" width=\"14\" height=\"22\" rx=\"7\" fill=\"#ffe2d1\" />\n" +
                        "              <rect class=\"eye\" x=\"121\" y=\"74\" width=\"14\" height=\"22\" rx=\"7\" fill=\"#ffe2d1\" />\n" +
                        "            </g>\n" +
                        "            <path d=\"M101 100q9 8 18 0\" stroke=\"#ffd9c4\" stroke-width=\"3.5\" stroke-linecap=\"round\" stroke-opacity=\"0.85\" />\n" +
                        "\n" +
                        "            <!-- 头上高光 -->\n" +
                        "            <rect x=\"72\" y=\"50\" width=\"26\" height=\"6\" rx=\"3\" fill=\"#fff\" fill-opacity=\"0.3\" />\n" +
                        "          </g>\n" +
                        "        </svg>\n" +
                        "      </div>\n" +
                        "\n" +
                        "      <!-- 终端 -->\n" +
                        "      <div class=\"terminal\">\n" +
                        "        <div class=\"term-bar\">\n" +
                        "          <span class=\"term-dot\"></span>\n" +
                        "          <span class=\"term-dot\"></span>\n" +
                        "          <span class=\"term-dot\"></span>\n" +
                        "          <span class=\"term-title\">~/projects/hero — claude</span>\n" +
                        "          <span class=\"term-live\"><i></i>live</span>\n" +
                        "        </div>\n" +
                        "        <pre class=\"term-body\" id=\"term\" aria-label=\"终端演示\"><span class=\"caret\"></span></pre>\n" +
                        "      </div>\n" +
                        "    </div>\n" +
                        "  </main>\n" +
                        "\n" +
                        "  <footer class=\"foot\">\n" +
                        "    <span>✻</span> Claude Code · 用键盘和好奇心驱动\n" +
                        "  </footer>\n" +
                        "\n" +
                        "  <script>\n" +
                        "    (function () {\n" +
                        "      const pre = document.getElementById('term');\n" +
                        "      const caret = pre.querySelector('.caret');\n" +
                        "      const robot = document.getElementById('robot');\n" +
                        "\n" +
                        "      // 每一行由若干 token 组成，token = { t: 文本, c: 颜色类名 }\n" +
                        "      const script = [\n" +
                        "        [\n" +
                        "          { t: '$ ', c: 'prompt' },\n" +
                        "          { t: 'claude', c: 'cmd' }\n" +
                        "        ],\n" +
                        "        [\n" +
                        "          { t: '✻ ', c: 'coral' },\n" +
                        "          { t: 'Reading ', c: 'mut' },\n" +
                        "          { t: 'src/Hero.tsx', c: 'path' },\n" +
                        "          { t: '…', c: 'mut' }\n" +
                        "        ],\n" +
                        "        [\n" +
                        "          { t: '● ', c: 'coral' },\n" +
                        "          { t: 'Update ', c: 'mut' },\n" +
                        "          { t: 'src/Hero.tsx', c: 'path' }\n" +
                        "        ],\n" +
                        "        [\n" +
                        "          { t: '   14 ', c: 'ln' },\n" +
                        "          { t: '− ', c: 'del' },\n" +
                        "          { t: '<Robot />', c: 'del' }\n" +
                        "        ],\n" +
                        "        [\n" +
                        "          { t: '   14 ', c: 'ln' },\n" +
                        "          { t: '+ ', c: 'add' },\n" +
                        "          { t: '<Robot bounce typing />', c: 'add' }\n" +
                        "        ],\n" +
                        "        [\n" +
                        "          { t: '✻ ', c: 'coral' },\n" +
                        "          { t: 'Running build…', c: 'mut' }\n" +
                        "        ],\n" +
                        "        [\n" +
                        "          { t: '✓ ', c: 'ok' },\n" +
                        "          { t: 'Built in ', c: 'mut' },\n" +
                        "          { t: '1.42s', c: 'ok' }\n" +
                        "        ]\n" +
                        "      ];\n" +
                        "\n" +
                        "      // 展平成字符流\n" +
                        "      const chars = [];\n" +
                        "      script.forEach(function (line, i) {\n" +
                        "        line.forEach(function (token) {\n" +
                        "          for (const ch of token.t) chars.push({ ch: ch, c: token.c });\n" +
                        "        });\n" +
                        "        if (i < script.length - 1) chars.push({ ch: '\\n', c: 'mut' });\n" +
                        "      });\n" +
                        "\n" +
                        "      const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;\n" +
                        "\n" +
                        "      function clearAll() {\n" +
                        "        Array.prototype.slice\n" +
                        "          .call(pre.querySelectorAll('span:not(.caret)'))\n" +
                        "          .forEach(function (el) { el.remove(); });\n" +
                        "      }\n" +
                        "\n" +
                        "      function addChar(item) {\n" +
                        "        const span = document.createElement('span');\n" +
                        "        if (item.c) span.className = item.c;\n" +
                        "        span.textContent = item.ch;\n" +
                        "        pre.insertBefore(span, caret);\n" +
                        "      }\n" +
                        "\n" +
                        "      if (reduce) {\n" +
                        "        chars.forEach(addChar);\n" +
                        "        return;\n" +
                        "      }\n" +
                        "\n" +
                        "      let index = 0;\n" +
                        "\n" +
                        "      function step() {\n" +
                        "        if (index >= chars.length) {\n" +
                        "          // 一段代码敲完 → 停顿 → 淡出 → 重来\n" +
                        "          setTimeout(function () {\n" +
                        "            pre.style.opacity = '0';\n" +
                        "            setTimeout(function () {\n" +
                        "              clearAll();\n" +
                        "              index = 0;\n" +
                        "              pre.style.opacity = '1';\n" +
                        "              setTimeout(step, 420);\n" +
                        "            }, 340);\n" +
                        "          }, 2400);\n" +
                        "          return;\n" +
                        "        }\n" +
                        "\n" +
                        "        const item = chars[index++];\n" +
                        "        addChar(item);\n" +
                        "\n" +
                        "        let delay;\n" +
                        "        if (item.ch === '\\n') delay = 105;\n" +
                        "        else if (item.ch === ' ') delay = 14;\n" +
                        "        else delay = 21 + Math.random() * 24;\n" +
                        "\n" +
                        "        setTimeout(step, delay);\n" +
                        "      }\n" +
                        "\n" +
                        "      // 开场先“醒”一下\n" +
                        "      setTimeout(step, 700);\n" +
                        "\n" +
                        "      // 点一下机器人：来个大跳\n" +
                        "      robot.addEventListener('click', function () {\n" +
                        "        robot.classList.remove('jump');\n" +
                        "        void robot.offsetWidth;\n" +
                        "        robot.classList.add('jump');\n" +
                        "        setTimeout(function () { robot.classList.remove('jump'); }, 780);\n" +
                        "      });\n" +
                        "      robot.addEventListener('animationend', function (e) {\n" +
                        "        if (e.animationName === 'superHop') robot.classList.remove('jump');\n" +
                        "      });\n" +
                        "    })();\n" +
                        "  </script>\n" +
                        "</body>\n" +
                        "</html>\n", CharsetUtil.UTF_8);
    }

    private WebSocketServerIndexPage() {
        // Unused
    }
}
