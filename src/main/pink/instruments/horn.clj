(ns pink.instruments.horn
 "Implementation of Andrew Horner and Lydia Ayer's French Horn models using
 banded wavetable synthesis. Based on the Csound code implementaiton."
 
(:require [pink.util :refer :all]
          [pink.config :refer [*buffer-size* *current-buffer-num* *sr*]]
          [pink.envelopes :refer :all]
          [pink.gen :refer [gen9 gen17]]
          [pink.oscillators :refer :all]
          [pink.filters :refer [tone atone]]
          [pink.space :refer [pan]]
          [pink.dynamics :refer [balance]]
          ))


(def ^:const ^:private ^{:tag 'long} 
  hwt-size 4096)
(def ^:const ^:private ^{:tag 'long} 
  horn-cutoff 2560)

;; TABLES 


;(def horn-cutoff [40 40 80 160 320 640 1280 2560 5120 10240 10240])

;;format: note-freq-max adjustment table0 [table1 table2]  
(def horn-wave-tables
 [
  [85 
   52.476
   sine-table
   (gen9 hwt-size [2 6.236] [3 12.827])
   (gen9 hwt-size [4 21.591] [5 11.401] [6 3.570] [7 2.833])
   (gen9 hwt-size [8 3.070] [9 1.053] [10 0.773] [11 1.349] [12 0.819] 
         [13 0.369] [14 0.362] [15 0.165] [16 0.124] [18 0.026] [19 0.042])]
  
  [114
   18.006
   sine-table
   (gen9 hwt-size [2 3.236] [3 6.827])
   (gen9 hwt-size [4 5.591] [5 2.401] [6 1.870] [7 0.733])
   (gen9 hwt-size [8 0.970] [9 0.553] [10 0.373] [11 0.549] [12 0.319] 
         [13 0.119] [14 0.092] [15 0.045] [16 0.034])]

  [153
   11.274
   sine-table
   (gen9 hwt-size [2 5.019] [3 4.281])
   (gen9 hwt-size [4 2.091] [5 1.001] [6 0.670] [7 0.233])
   (gen9 hwt-size [8 0.200] [9 0.103] [10 0.073] [11 0.089] [12 0.059] 
         [13 0.029])]

  [204
   6.955
   sine-table
   (gen9 hwt-size [2 4.712] [3 1.847])
   (gen9 hwt-size [4 0.591] [5 0.401] [6 0.270] [7 0.113])
   (gen9 hwt-size [8 0.060] [9 0.053] [10 0.023])]

  [272 
   2.260
   sine-table
   (gen9 hwt-size [2 1.512] [3 0.247])
   (gen9 hwt-size [4 0.121] [5 0.101] [6 0.030] [7 0.053])
   (gen9 hwt-size [8 0.030])]

  [364
   1.171
   sine-table
   (gen9 hwt-size [2 0.412] [3 0.087])
   (gen9 hwt-size [4 0.071] [5 0.021])]

  [486
   1.106
   sine-table
   (gen9 hwt-size [2 0.309] [3 0.067])
   (gen9 hwt-size [4 0.031])]

  [Integer/MAX_VALUE
   1.019
   sine-table
   (gen9 hwt-size [2 0.161] [3 0.047])]

  ])


;(def horn-stopped-cutoff [40 40 80 160 320 640 1280 2560 5120 10240 10240])


(def horn-stopped-wave-tables
 [
  [272
   3.172
   sine-table
   (gen9 hwt-size [2 0.961] [3 0.052])
   (gen9 hwt-size [4 0.079] [5 0.137] [6 0.185] [7 0.109]) 
   (gen9 hwt-size [8 0.226] [9 0.107] [10 0.155] [11 0.140] [12 0.428] 
         [13 0.180] [15 0.070] [16 0.335] [17 0.183] [18 0.073] [19 0.172] 
         [20 0.117] [21 0.089] [22 0.193] [23 0.119] [24 0.080] [25 0.36] 
         [26 0.143] [27 0.036] [28 0.044] [29 0.040] [30 0.052] [31 0.086]
         [32 0.067] [33 0.097] [34 0.046] [36 0.030] [37 0.025] [38 0.048]
         [39 0.021] [40 0.025])]

  [363
   1.947
   sine-table
   (gen9 hwt-size [2 0.162] [3 0.068])
   (gen9 hwt-size [4 0.116] [5 0.13] [6 0.050] [7 0.089])
   (gen9 hwt-size [8 0.156] [9 0.381] [10 0.191] [11 0.126] [12 0.162] 
         [13 0.073] [15 0.157] [16 0.074] [17 0.087] [18 0.151] [19 0.093] 
         [20 0.031] [21 0.030] [22 0.051] [23 0.058] [24 0.051] [25 0.077] 
         [26 0.033] [27 0.021] [28 0.039])]

  [484
   2.221
   sine-table
   (gen9 hwt-size [2 0.164] [3 0.164])
   (gen9 hwt-size [4 0.401] [5 0.141] [6 0.293] [7 0.203])
   (gen9 hwt-size [8 0.170] [9 0.306] [10 0.170] [11 0.103] 
         [12 0.131] [13 0.134] [14 0.047] [15 0.182] [16 0.049] [17 0.088] 
         [18 0.088] [19 0.064] [20 0.024] [21 0.064] [22 0.022])]

  [Integer/MAX_VALUE 
   2.811
   sine-table
   (gen9 hwt-size [2 0.193] [3 0.542])
   (gen9 hwt-size [4 0.125] [5 0.958] [6 0.154] [7 0.364])
   (gen9 hwt-size [8 0.444] [9 0.170] [10 0.090] [11 0.077] [12 0.026] 
         [13 0.073])]
 
  ])


(defn horn-lookup
  "Returns the wavetable set for a given frequency and bank of wavetable sets"
  [^double freq tbls] 
  (loop [[x & xs] tbls]
    (if (nil? xs) 
      (rest x)
      (if (< freq ^double (first x))
        (rest x)
        (recur xs)))))

; audio generator functions

(defn horn-play
  [amp freq wave-tables]
  (let [env0 (shared 
               (if (number? amp)
                 (let [a (double amp)] 
                   (env [0 0 0.02 a 0.03 (* 0.9 a) 0.5 (* 0.9 a) 0.2 0.0] ))
                 (arg amp)))
        env1 (shared (mul env0 env0))
        env2 (shared (mul env1 env0))
        env3 (shared (mul env2 env0))
        envs [env0 env1 env2 env3]
        freqf (shared (arg freq))
        phase 0.5
        [adjust & tbls] (horn-lookup freq wave-tables) 
        tbl-fns (map oscil3 envs (repeat freqf) tbls (repeat phase))
        portamento (sum 1.0 (oscil3 0.02 0.5 sine-table))] 
    (let-s [asig (div (apply sum tbl-fns) (arg adjust))]
      (mul portamento 
           (balance (tone asig horn-cutoff) asig)))
    ))

(defn horn
  "Creates mono horn unless panning given"
  ([amp freq] (horn amp freq nil))
  ([amp freq loc]
   (if (nil? loc)
     (horn-play amp freq horn-wave-tables)
     (pan (horn-play amp freq horn-wave-tables) loc))))

(defn horn-stopped
  "Creates mono stopped horn unless panning given"
  ([amp freq] (horn-stopped amp freq nil))
  ([amp freq loc]
   (if (nil? loc)
     (horn-play amp freq horn-stopped-wave-tables)
     (pan (horn-play amp freq horn-stopped-wave-tables) loc))))

(defn- horn-straight-mute
  [asig]
  (let-s [sig asig]
    (balance (atone sig 1200) sig)) )

(defn horn-muted
  "Creates mono muted horn unless panning given"
  ([amp freq] (horn-muted amp freq nil))
  ([amp freq loc]
   (if (nil? loc)
     (horn-straight-mute 
       (horn-play amp freq horn-wave-tables)) 
     (pan 
       (horn-straight-mute 
         (horn-play amp freq horn-wave-tables)) loc))))
