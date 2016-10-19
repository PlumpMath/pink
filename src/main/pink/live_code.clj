(ns pink.live-code
  "Functions for live coding music using pink.simple engine."
  (:require [pink.simple :refer :all]
            [pink.config :refer :all]
            [pink.envelopes :refer :all]
            [pink.util :refer :all]
            [pink.node :refer :all]
            [pink.oscillators :refer :all]
            [pink.space :refer :all]
            [pink.event :refer :all]
            [pink.effects.reverb :refer :all]
            [pink.io.sound-file :refer :all]
            [clojure.string :refer [join]]
            ) 
  )

;; One-shot Sample Player 

(defn sample-duration 
  "Return duration of audio sample"
  [wave]
  (let [d ^doubles (aget ^"[[D"(:data wave) 0)]
    ;; TODO - *sr* may require replacement with an (sr) function
    ;; to simplify getting sr value from pink.simple engine
    (/ (alength d) (double *sr*)))) 

(defn sample-one-shot
  "Returns audio function that plays a stereo sample without looping until
  completion."
  ([sample]
   (let [dur (sample-duration sample)]
     (mul (env [0 0 0.001 0.5 (- dur 0.002) 0.5 0.001 0])
          (oscili 1.0 (/ 1.0 dur) 
                  (aget ^"[[D" (:data sample) 0))
          ))))

;; event/time functions

(defn cause [func start & args]
  "Implementation of Canon-style cause function. Will create and schedule an
  event that calls the given function at given start time (in beats) and with
  given arguments."
  (add-events (apply event func start args)))

(defmacro redef! 
  "Macro to redefine given function 'a' to function value held in new-func.
  Useful in conjunction with cause to schedule when a function will be
  redefined. For example, when modifying a temporally recursive function, one
  can use (cause #(redef! a b) (next-beat 16)) to schedule that the function 'a'
  be redefined with value of 'b' at the next 4-bar boundary. Users may then
  edit body of function separately from its use in performance."
  [a new-func] 
  `(def ~a ~new-func))

(defmacro kill-recur! 
  "Macro to redefine given function name to a var-arg, no-op function. Useful
  to end temporal recursion of an event function that may be already queued up
  in the event list. 
  
  When using kill-recur!, be aware that the function def in memory no longer
  represents what is on screen. User will need to re-evaluate the function
  definition before using again in temporal recursion.
 
  User may schedule a call to kill-recur by using wrapper function, such as:

  (cause #(kill-recur! my-perf-func) (next-beat 4)) 

  Note: Implemented as macro to work with var special-form."
  [a]
  `(defn ~a [& args#]))


(defn next-beat 
  "Calculates forward time for the next beat boundary.  Useful for scheduling
  of temporally recursive event function so that it will fire in sync with
  other beat-oriented functions.Adjusts for fractional part of current beat
  time that arises due to block-based processing. 
  
  For example, if an engine has a current beat time of 81.2, if (next-beat 4)
  is used, it will provide a value of 3.8, so that the event function will fire
  at beat 84."
  (^double [] (next-beat (now) 1.0))
  (^double [b] (next-beat (now) b))
  (^double [cur-beat-time b]
   (let [beat cur-beat-time 
         base (Math/floor (/ beat b))]
     (double (- (* (inc base) b) beat)))))

(defn beat-mod 
  "Returns modulus division of given beat-time and mod-val. Rounds to whole
  number beats. Defaults to using (now) if only mod-val is given. 2-arity
  version useful with multiplied versions of (now) to get sub-divisions of
  beat."
  ([mod-val] (beat-mod (now) mod-val))
  ([beat-time mod-val]
  (long (Math/round (double (mod beat-time mod-val))))))


(defn beats 
  "Returns time in seconds for num beats given. Useful for converting times
  appropriate for audio functions."
  [b]
  (beats->seconds b (tempo)))

(defn bars
  "Returns time in seconds for num bars given. Useful for converting times
  appropriate for audio functions."
  ([b]
   (bars b 4))
  ([b beats-per-bar]
   (beats (* b beats-per-bar))))


;; visualization

(defn beat-printer 
  "Temporally recursive function to print out beat-mod
  times.  Useful for seeing where one is within the current
  beat/bar structure."
  [& args]
  (when (pos? (count args))
    (let [fmt (join " " (repeat (count args) "%2d"))]
      (print "\r")
      (apply printf fmt (map beat-mod args))
      (.flush *out*)
      (cause beat-printer (next-beat) args)
      )))
