(ns flappy-bird-demo.core
  (:require
   [cljsjs.react] ;; 用了原始的react,而没有用om或者是reagent
   [cljsjs.react.dom] ;;虚拟dom
   [sablono.core :as sab :include-macros true] ;;hiccup类似的html输出for react
   [cljs.core.async :refer [<! chan sliding-buffer put! close! timeout]]) ;;异步处理
  (:require-macros
   [cljs.core.async.macros :refer [go-loop go]])) ;;异步处理

;; TODOS: ----->>>>
;; 1. 飞行高度的控制: 点击的位置的次数
;; 2. 碰到柱子结束游戏
;; 3. 越过柱子加分
;; 4. 时间作为函数: 飞行的位置
;; 5. readme为量化的游戏规则
(defn readme
  []
  {:val {:jump-count 3, ;; 跳的次数
         :flappy-start-time 3904182.238999987, ;; 飞行的开始时间
         :time-delta 366.82800005655736, ;; delta是飞行的三角洲
         :start-time 3899079.99499992, ;;开始时间
         :initial-vel 21, ;; initial最初的vel
         :timer-running false, ;; timer定时器,一切的核心是时间,运行时间,争取时间
         :flappy-y 198.44712756106685, ;;上下的移动距离, 掉到地上是520,
         ;;flappy-y无限的上升是-1059或者是更小
         ;;初始的上面柱子的高度是200,当飞行距离flappy-y小于200时And时间轴在柱子里时就会撞到柱子
         ;; ===>>> FRP: 游戏编程的本质是repl交互式编程,不断的和计算机原有的过滤规则交互,找到可以通过的数据流
         ;; ====>>> 初始的下面的柱子是900时,飞行高度flappy为325就会碰到下面的柱子
         ;;==================================>>>>>> 柱子list的说明=====>>>
         ;; 点开始时: [{:start-time 0, :pos-x 900, :cur-x 900, :gap-top 200}]
         ;; 点第二下时: ({:start-time 70281.98999993037, :pos-x 900, :cur-x 671, :gap-top 200} {:start-time 70281.98999993037, :pos-x 1224, :cur-x 995, :gap-top 66} {:start-time 70348.68599998299, :pos-x 1537, :cur-x 1318, :gap-top 143})
         :score 1, ;;得到的分数
         :cur-time 3904182.238999987, ;;当前的时间
         :pillar-list ;;pillar柱子的列表, 要躲过所有列表的的过滤器,才能成功
         (list
          {:start-time 3899079.99499992, ;; 柱子出现的时间
           :pos-x 900, ;; 下面柱子的高度
           :cur-x 134, ;; 时间轴x的位置
           :gap-top 200} ;;上面柱子的高度
          {:start-time 3899079.99499992,
           :pos-x 1224,
           :cur-x 458,
           :gap-top 95}
          {:start-time 3899130.0169999013,
           :pos-x 1540,
           :cur-x 782,
           :gap-top 216})}}
  )

(enable-console-print!)
;; (floor "2.1") ;;=> 2 去除小数点
(defn floor [x] (.floor js/Math x)) 
;; 转换=> 位置和时间
(defn translate [start-pos vel time]
  (floor (+ start-pos (* time vel))))

(def horiz-vel -0.15)
(def gravity 0.05)
(def jump-vel 21)
(def start-y 312)
(def bottom-y 561)
(def flappy-x 212)
(def flappy-width 57)
(def flappy-height 41)
(def pillar-spacing 324)
(def pillar-gap 158) ;; 158
(def pillar-width 86)

(def starting-state { :timer-running false
                      :jump-count 0
                      :initial-vel 0
                      :start-time 0
                      :flappy-start-time 0
                      :flappy-y   start-y
                      :pillar-list
                      [{ :start-time 0
                         :pos-x 900
                         :cur-x 900
                         :gap-top 200 }]})

(defn reset-state [_ cur-time]
  (-> starting-state
      (update-in [:pillar-list] (fn [pls] (map #(assoc % :start-time cur-time) pls)))
      (assoc
          :start-time cur-time
          :flappy-start-time cur-time
          :timer-running true)))

(defonce flap-state (atom starting-state))

;;pillar是当前的柱子定位: 当前时间cur-time, pos-x, start-time开始时间 作为参赛
;; 当前时间减去开始的时间 
(defn curr-pillar-pos [cur-time {:keys [pos-x start-time] }]
  (translate pos-x horiz-vel (- cur-time start-time)))

(defn in-pillar? [{:keys [cur-x]}]
  (and (>= (+ flappy-x flappy-width)
           cur-x)
       (< flappy-x (+ cur-x pillar-width))))

(defn in-pillar-gap? [{:keys [flappy-y]} {:keys [gap-top]}]
  (and (< gap-top flappy-y)
       (> (+ gap-top pillar-gap)
          (+ flappy-y flappy-height))))

(defn bottom-collision? [{:keys [flappy-y]}]
  (>= flappy-y (- bottom-y flappy-height)))

;; 碰撞死了
(defn collision? [{:keys [pillar-list] :as st}]
  (if (some #(or (and (in-pillar? %)
                      (not (in-pillar-gap? st %)))
                 (bottom-collision? st)) pillar-list)
    (assoc st :timer-running false) ;;时间run就停止了=> 生命的时间就停止了😂  😂  😂
    st))

(defn new-pillar [cur-time pos-x]
  {:start-time cur-time
   :pos-x      pos-x
   :cur-x      pos-x
   :gap-top    (+ 60 (rand-int (- bottom-y 120 pillar-gap)))})

(defn update-pillars [{:keys [pillar-list cur-time] :as st}]
  (let [pillars-with-pos (map #(assoc % :cur-x (curr-pillar-pos cur-time %)) pillar-list)
        pillars-in-world (sort-by
                          :cur-x
                          (filter #(> (:cur-x %) (- pillar-width)) pillars-with-pos))]
    (assoc st
      :pillar-list
      (if (< (count pillars-in-world) 3)
        (conj pillars-in-world
              (new-pillar
               cur-time
               (+ pillar-spacing
                  (:cur-x (last pillars-in-world)))))
        pillars-in-world))))

(defn sine-wave [st]
  (assoc st
    :flappy-y
    (+ start-y (* 30 (.sin js/Math (/ (:time-delta st) 300))))))

(defn update-flappy [{:keys [time-delta initial-vel flappy-y jump-count] :as st}]
  (if (pos? jump-count)
    (let [cur-vel (- initial-vel (* time-delta gravity))
          new-y   (- flappy-y cur-vel)
          new-y   (if (> new-y (- bottom-y flappy-height))
                    (- bottom-y flappy-height)
                    new-y)]
      (assoc st
        :flappy-y new-y))
    (sine-wave st)))

(defn score [{:keys [cur-time start-time] :as st}]
  (let [score (- (.abs js/Math (floor (/ (- (* (- cur-time start-time) horiz-vel) 544)
                               pillar-spacing)))
                 4)]
  (assoc st :score (if (neg? score) 0 score))))

(defn time-update [timestamp state]
  (-> state
      (assoc
          :cur-time timestamp
          :time-delta (- timestamp (:flappy-start-time state)))
      update-flappy
      update-pillars
      collision?
      score))

(defn jump [{:keys [cur-time jump-count] :as state}]
  (-> state
      (assoc
          :jump-count (inc jump-count)
          :flappy-start-time cur-time
          :initial-vel jump-vel)))

;; derivatives

(defn border [{:keys [cur-time] :as state}]
  (-> state
      (assoc :border-pos (mod (translate 0 horiz-vel cur-time) 23))))

(defn pillar-offset [{:keys [gap-top] :as p}]
  (assoc p
    :upper-height gap-top
    :lower-height (- bottom-y gap-top pillar-gap)))

(defn pillar-offsets [state]
  (update-in state [:pillar-list]
             (fn [pillar-list]
               (map pillar-offset pillar-list))))

(defn world [state]
  (-> state
      border
      pillar-offsets))

(defn px [n] (str n "px"))

;; 柱子的视图: (first (:pillar-list @flap-state)) ;; => {:start-time 17636.856000004627, :pos-x 900, :cur-x 232, :gap-top 200}
(defn pillar [{:keys [cur-x pos-x upper-height lower-height]}]
  [:div.pillars
   ;; cur-x 是上下柱子的左右位置,横轴X轴的位置
   ;; 上面的柱子: left: 232px; height: 200px; upper-height 是上面柱子的高度
   [:div.pillar.pillar-upper {:style {:left (px cur-x)
                                      :height upper-height}}]
   ;; 下面的柱子: left: 232px; height: 203px; lower-height 是下面柱子的高度
   [:div.pillar.pillar-lower {:style {:left (px cur-x)
                                      :height lower-height}}]])

(defn time-loop [time]
  (let [new-state (swap! flap-state (partial time-update time))]
    (when (:timer-running new-state)
      (go
       (<! (timeout 30))
       (.requestAnimationFrame js/window time-loop)))))

(defn start-game []
  (.requestAnimationFrame
   js/window
   (fn [time]
     (reset! flap-state (reset-state @flap-state time))
     (time-loop time))))

(defn is-vibration-supported?
  "Return true if the navigator contains vibrate property."
  []
  (exists? js/navigator.vibrate))

;; (vibrate 1000 2000 3000)
;; (vibrate 1000)
(defn vibrate
  "If vibration supported by browser device will vibrate 
   by odd elements of duration vector with pauses by even 
   elements of vector."
  [duration]
  (if (is-vibration-supported?)
    (js/navigator.vibrate (clj->js duration))))

(defn main-template [{:keys [score cur-time jump-count
                             timer-running border-pos
                             flappy-y pillar-list]}]
  (sab/html [:div.board { :onMouseDown (fn [e]
                                         ;; 鼠标单击修改飞行高度
                                         (swap! flap-state jump)
                                         ;;用振动来表示跳动
                                         (vibrate 200)
                                         (prn (str "AAAA" (:pillar-list @flap-state)))
                                         (.preventDefault e))}
             (if (:timer-running @flap-state)
               [:h1.score]
               [:h1.score score]) 
             [:h5.notice ;;gap-top是上面柱子的高度
              ;; (clojure.string/join ", " (map #(:gap-top %) (:pillar-list @flap-state)))
              (let [aa "↑"
                    bb "↓" 
                    datas (map #(:gap-top %) (:pillar-list @flap-state))]
                (if (= (count datas) 3)
                  (let [data datas
                        fnv #(cond (> %1 %2)
                                   (if (> (js/Math.abs (- %1 %2)) 99)
                                     (str aa aa) aa)
                                   :else
                                   (if (> (js/Math.abs (- %1 %2)) 99)
                                     (str bb bb) bb)
                                   )
                        n0 (nth data 0)
                        n1 (nth data 1)
                        n2 (nth data 2)]
                    (clojure.string/join " " (list n0 (fnv n0 n1) n1 (fnv n1 n2) n2)) )
                  (clojure.string/join ", " datas)
                  )
                )
              ;;
              ]
             (if-not timer-running
               [:a.start-button {:onClick #(start-game)}
                (if (< 1 jump-count) "RESTART" "START")]
               [:span])
             [:div (map pillar pillar-list)]
             [:div.flappy {:style {:top (px flappy-y)}}]
             ;; border-pos 是变化的背景
             [:div.scrolling-border {:style { :background-position-x (px border-pos)}}]]))

(let [node (.getElementById js/document "board-area")]
  (defn renderer [full-state]
    (.render js/ReactDOM (main-template full-state) node)))

(add-watch flap-state :renderer (fn [_ _ _ n]
                                  (renderer (world n))))

(reset! flap-state @flap-state)
