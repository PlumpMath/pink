(ns pink.oscillators
  "Oscillator Functions"
  (:require [pink.config :refer [*sr* *buffer-size*]]
            [pink.util :refer [create-buffer fill map-d 
                                     swapd! setd! getd arg]]
            [pink.gen :refer [gen-sine]] 
            ))

(def ^:const PI Math/PI)

(defmacro dec-if 
  [a] 
  `(if (>= ~a 1.0) (dec ~a) ~a))

(defn phasor 
  "Phasor with fixed frequency and starting phase"
  [^double freq ^double phase]
  (let [phase-incr ^double (/ freq  *sr*)
        cur-phase (double-array 1 phase)
        out (create-buffer)]
      (fn ^doubles [] 
        (fill out cur-phase #(dec-if (+ phase-incr ^double %))))))

(defn sine 
  "Sine generator with fixed frequency and starting phase"
  ([^double freq]
   (sine freq 0.0))
  ([^double freq ^double phase]
   (let [phsr (phasor freq phase)
         out (create-buffer)]
     (fn ^doubles []
       (map-d out #(Math/sin (* 2.0 PI ^double %)) (phsr))))))


(defmacro phs-incr
  [cur incr]
  `(dec-if (+ ~cur ~incr)))

(defn vphasor 
  "Phasor with variable frequency and fixed starting phase."
  [freq phase]
  {:pre (number? phase)}
  (let [out ^doubles (create-buffer)
        cur-phase (double-array 1 phase)
        len (alength ^doubles out)
        lastindx (dec len)
        ffn (arg freq)]
    (fn ^doubles [] 
      (let [f (ffn) ]
        (when f 
          (loop [i (unchecked-int 0)]
            (when (< i len)
              (let [incr ^double (/ (aget ^doubles f i) *sr*)] 
                (aset out i 
                      (setd! cur-phase (phs-incr (getd cur-phase) incr)))
                (recur (unchecked-inc-int i)))) 
            )
          out)))))

(defn sine2 
  "Sine generator with variable frequency and fixed starting phase."
  ([f]
   (sine2 f 0))
  ([f p]
   (let [phsr (vphasor (arg f) p)
         out (create-buffer)]
     (fn ^doubles []
       (map-d out #(Math/sin (* 2.0 PI ^double %)) (phsr))))))

(def sine-table (gen-sine))

;; fixme - handle amplitude as function by updating map-d to take in multiple buffers
(defn oscil
  "Oscillator with table (defaults to sine wave table, truncates indexing)"
  ([amp freq]
   (oscil amp freq sine-table 0))
  ([amp freq table]
   (oscil amp freq table 0))
  ([amp freq ^doubles table phase]
   (let [phsr (vphasor (arg freq) phase)
         out (create-buffer)
         tbl-len (alength table)
         ampfn (arg amp)]
      (fn ^doubles []
        (map-d out #(* %2 (aget table (int (* % tbl-len)))) (phsr) (ampfn))))))


(defn oscili
  "Linear-interpolating oscillator with table (defaults to sine wave table)"
  ([amp freq]
   (oscili amp freq sine-table 0))
  ([amp freq table]
   (oscili amp freq table 0))
  ([amp freq ^doubles table phase]
   (let [phsr (vphasor (arg freq) phase)
         out (create-buffer)
         tbl-len (alength table)
         ampfn (arg amp)]
      (fn ^doubles []
        (map-d out 
               #(let [phs (* % tbl-len)
                      pt0 (int phs)
                      pt1 (mod (inc pt0) tbl-len)  
                      frac (if (zero? pt0) 
                             phs
                             (rem phs pt0))
                      v0  (aget table pt0)
                      v1  (aget table pt1)]
                 (* %2 
                   (+ v0 (* frac (- v1 v0))))) 
               (phsr) (ampfn))))))


(defn oscil3
  "Cubic-interpolating oscillator with table (defaults to sine wave table) (based on Csound's oscil3)"
  ([amp freq]
   (oscil3 amp freq sine-table 0))
  ([amp freq table]
   (oscil3 amp freq table 0))
  ([amp freq ^doubles table phase]
   (let [phsr (vphasor (arg freq) phase)
         out (create-buffer)
         tbl-len (alength table)
         ampfn (arg amp)]
      (fn ^doubles []
        (map-d out 
               #(let [phs (* % tbl-len)
                      pt1 (int phs)
                      pt0 (if (zero? pt1) (- tbl-len 1) (- pt1 1))  
                      pt2 (mod (inc pt1) tbl-len)  
                      pt3 (mod (inc pt2) tbl-len)  
                      x (if (zero? pt1) 
                             phs
                             (rem phs pt1))
                      x2 (* x x)
                      x3 (* x x2)
                      p0  (aget table pt0)
                      p1  (aget table pt1)
                      p2  (aget table pt2)
                      p3  (aget table pt3)
                      a (/ (+ p3 (* -3 p2) (* 3 p1) (* -1 p0)) 6)                      
                      b (/ (+ p2 (* -2 p1) p0) 2)
                      c (+ (* p3 (double -1/6)) p2 (* p1 (double -1/2)) (* p0 (double -1/3)))
                      d p1 ]
                 (* %2 
                   (+ (* a x3) (* b x2) (* c x) d))) 
               (phsr) (ampfn))))))


;; Implementation of Bandlimited Impulse Train (BLIT) functions by Stilson and
;; Smith. Based on implementations from Synthesis Toolkit (STK)

(defmacro calc-harmonics 
  [p nharmonics]
  `(if (<= ~nharmonics 0)
    (let [max-harmonics# (Math/floor (* 0.5 ~p))]
      (+ (* 2 max-harmonics#) 1))
    (+ (* 2 ~nharmonics) 1)))

(defmacro pi-limit
  [v]
  `(if (>= ~v Math/PI) (- ~v Math/PI) ~v))

(def DOUBLE-EPSILON
  (Math/ulp 1.0))

(defn blit-saw
  "Implementation of BLIT algorithm by Stilson and Smith for band-limited
  sawtooth waveform. Based on the C++ implementation from STK.
 
  Returns an optimized audio-function if freq is a number, or a slower
  version if freq is itself an audio-function."
  ([freq] (blit-saw freq 0))
  ([freq nharmonics]
   {:pre [(or (and (number? freq) (pos? freq)) (fn? freq))] }
  (if (number? freq)
    (let [out ^doubles (create-buffer)
          state (double-array 1 0) 
          phs (double-array 1 0)
          p (/ *sr* freq)
          c2 (/ 1 p)
          rate (* Math/PI c2)
          m (calc-harmonics p nharmonics)
          a (/ m p)]
      (fn []
        (loop [i 0 phase (aget phs 0) st (aget state 0)]
          (if (< i *buffer-size*)
            (let [denom (Math/sin phase)
                  tmp (+ (- st c2) 
                         (if (<= (Math/abs denom) DOUBLE-EPSILON)
                           a
                           (/ (Math/sin (* m phase)) (* p denom))))
                  new-st (* tmp 0.995)
                  new-phs (pi-limit (+ phase rate))]
                (aset out i tmp) 
                (recur (unchecked-inc i) new-phs new-st) 
              ) 
            (do
              (aset phs 0 phase)
              (aset state 0 st)
              out)))))
    (let [out ^doubles (create-buffer)
          state (double-array 1 0)
          phs (double-array 1 0)]
      (fn []
        (when-let [freq-sig ^doubles (freq)]
          (loop [i 0 phase (aget phs 0) st (aget state 0)]
            (if (< i *buffer-size*)
              (let [f (aget freq-sig i)]
                (if (zero? f)
                  (do 
                    (aset out i 0.0)
                    (recur (unchecked-inc i) phase st))
                  (let [denom (Math/sin phase)
                        p (/ *sr* (aget freq-sig i))
                        c2 (/ 1 p)
                        rate (* Math/PI c2)
                        m (calc-harmonics p nharmonics)
                        a (/ m p)
                        tmp (+ (- st c2) 
                               (if (<= (Math/abs denom) DOUBLE-EPSILON)
                                 a
                                 (/ (Math/sin (* m phase)) (* p denom))))
                        new-st (* tmp 0.995)
                        new-phs (pi-limit (+ phase rate))]
                    (aset out i tmp) 
                    (recur (unchecked-inc i) new-phs new-st) 
                    ))) 
              (do
                (aset phs 0 phase)
                (aset state 0 st)
                out))))

        ) 
      )
    )))
