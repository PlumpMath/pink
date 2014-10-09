(ns pink.engine
  "Audio Engine Code"
  (:require [pink.config :refer :all]
            [pink.util :refer :all]
            [pink.event :refer :all]
            [pink.io.audio :refer :all])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream File] 
           [java.nio ByteBuffer]
           [java.util Arrays]
           [javax.sound.sampled AudioFormat AudioSystem SourceDataLine
                                AudioFileFormat$Type AudioInputStream]
           [pink.event Event]))


(defmacro limit [num]
  `(if (> ~(tag-double num) Short/MAX_VALUE)
    Short/MAX_VALUE 
    (if (< ~(tag-double num) Short/MIN_VALUE)
      Short/MIN_VALUE 
      (short ~num))))

;;;; Engine

(def engines (atom []))

(def BYTE-SIZE (/ Short/SIZE Byte/SIZE)) ; 2 bytes for 16-bit audio

(deftype Engine [status clear pending-afuncs
                   pending-pre-cfuncs pending-post-cfuncs
                   sample-rate nchnls buffer-size
                   out-buffer-size byte-buffer-size
                   event-list]
  Object
  (toString [this] (str "[Engine] ID: " (System/identityHashCode this) " Status " @status)))

(defn engine-create 
  "Creates an audio engine"
  [& {:keys [sample-rate nchnls buffer-size] 
      :or {sample-rate 44100 nchnls 1 buffer-size 64}}] 
  (let  [e 
          (Engine. (atom :stopped) (atom false) (atom [])
            (atom []) (atom [])
            sample-rate nchnls buffer-size 
            (* buffer-size nchnls) (* BYTE-SIZE buffer-size nchnls)
            (event-list))]
    (swap! engines conj e) 
    e))

;; should do initialization of f on separate thread?
(defn engine-add-afunc 
  [^Engine engine f]
  (swap! (.pending-afuncs engine ) conj f))

(defn engine-remove-afunc 
  [^Engine engine f]
  (throw (Exception. "Not yet implemented"))) 

(defn engine-add-pre-cfunc 
  [^Engine engine f]
  (swap! (.pending-pre-cfuncs engine) conj f))

(defn engine-remove-pre-cfunc 
  [^Engine engine f]
  (throw (Exception. "Not yet implemented"))) 

(defn engine-add-post-cfunc 
  [^Engine engine f]
  (swap! (.pending-post-cfuncs engine) conj f))

(defn engine-remove-post-cfunc 
  [^Engine engine f]
  (throw (Exception. "Not yet implemented"))) 

(defn engine-add-events 
  [^Engine engine events]
  (event-list-add (.event-list engine) events))

;;;; JAVASOUND CODE

(defmacro doubles->byte-buffer 
  "Write output from doubles array into ByteBuffer. 
  Maps -1.0,1.0 to Short/MIN_VALUE,Short/MAX_VALUE, truncating
  values outside of -1.0,1.0."
  [dbls buf]
  `(let [len# (alength ~dbls)]
    (loop [y# 0]
      (when (< y# len#)
        (.putShort ~buf (limit (* Short/MAX_VALUE (aget ~dbls y#))))  
        (recur (unchecked-inc y#))))))


(defn- write-asig
  "Writes asig as a channel into an interleaved out-buffer"
  [^doubles out-buffer ^doubles asig chan-num]
  (if (= *nchnls* 1)
    (when (= 0 chan-num)
      (map-d out-buffer + out-buffer asig))
    (loop [i 0]
      (when (< i *buffer-size*)
        (let [out-index (+ chan-num (* i *nchnls*))] 
          (aset out-buffer out-index
            (+ (aget out-buffer out-index) (aget asig i))))
        (recur (unchecked-inc-int i))))))

(defmacro run-audio-funcs [afs buffer]
  (let [x (gensym)
        b (gensym)]
    `(loop [[~x & xs#] ~afs 
            ret# []]
       (if ~x 
         (if-let [~b (try-func (~x))]
           (do 
             (if (multi-channel? ~b)
               (loop [i# 0 len# (count ~b)]
                 (when (< i# len#)
                   (write-asig ~buffer 
                               (aget ~(with-meta b {:tag "[[D"}) i#) i#)
                   (recur (unchecked-inc-int i#) len#))) 
               (write-asig ~buffer ~b 0))
             ;(map-d ~buffer + b# ~buffer)
             (recur xs# (conj ret# ~x)))
           (recur xs# ret#))
         ret#)))) 

(defn- process-buffer
  [afs ^doubles out-buffer ^ByteBuffer buffer]
  (Arrays/fill ^doubles out-buffer 0.0)
  (let [newfs (run-audio-funcs afs out-buffer)]
    (doubles->byte-buffer out-buffer buffer)
    newfs))

(defn- process-cfuncs
  [cfuncs]
  (loop [[x & xs] cfuncs
         ret []]
    (if x
      (if (try-func (x)) 
        (recur xs (conj ret x))
        (recur xs ret))
      ret)))

(defn- buf->line [^ByteBuffer buffer ^SourceDataLine line
                 ^long out-buffer-size]
  (.write line (.array buffer) 0 out-buffer-size)
  (.clear buffer))

(defn engine-run 
  "Main realtime engine running function. Called within a thread from
  engine-start."
  [^Engine engine]
  (let [af (AudioFormat. (.sample-rate engine) 16 (.nchnls engine) true true)
        #^SourceDataLine line (open-line af)        
        out-buffer (double-array (.out-buffer-size engine))
        buf (ByteBuffer/allocate (.byte-buffer-size engine))
        pending-afuncs (.pending-afuncs engine)
        pending-pre-cfuncs (.pending-pre-cfuncs engine)
        pending-post-cfuncs (.pending-post-cfuncs engine)
        clear-flag (.clear engine)
        sr (.sample-rate engine)
        buffer-size (.buffer-size engine)
        nchnls (.nchnls engine) 
        run-engine-events (event-list-processor (.event-list engine))]
    (loop [pre-cfuncs (drain-atom! pending-pre-cfuncs)
           cur-funcs (drain-atom! pending-afuncs) 
           post-cfuncs (drain-atom! pending-post-cfuncs)
           buffer-count 0]
      (if (= @(.status engine) :running)
        (do 
          (binding [*current-buffer-num* buffer-count 
                    *sr* sr 
                    *buffer-size* buffer-size 
                    *nchnls* nchnls]
            (run-engine-events)) 
          (let [pre (binding [*current-buffer-num* buffer-count 
                              *sr* sr 
                              *buffer-size* buffer-size 
                              *nchnls* nchnls] 
                      (process-cfuncs (concat-drain! pre-cfuncs pending-pre-cfuncs)))
                afs (binding [*current-buffer-num* buffer-count 
                              *sr* sr 
                              *buffer-size* buffer-size 
                              *nchnls* nchnls]
                      (process-buffer (concat-drain! cur-funcs pending-afuncs) out-buffer buf))
                post (binding [*current-buffer-num* buffer-count 
                               *sr* sr 
                               *buffer-size* buffer-size 
                               *nchnls* nchnls]
                       (process-cfuncs (concat-drain! post-cfuncs pending-post-cfuncs)))]  
            (buf->line buf line (.byte-buffer-size engine))

            (if @clear-flag
              (do 
                (reset! pending-pre-cfuncs [])
                (reset! pending-afuncs [])
                (reset! pending-post-cfuncs [])
                (reset! clear-flag false)
                (event-list-clear (.event-list engine))
                (recur [] [] [] (unchecked-inc buffer-count)))
              (recur pre afs post (unchecked-inc buffer-count)))))
        (do
          (println "stopping...")
          (doto line
            (.flush)
            (.close)))))))

(defn engine-start [^Engine engine]
  (when (= @(.status engine) :stopped)
    (reset! (.status engine) :running) 
    (.start (Thread. ^Runnable (partial engine-run engine)))))

(defn engine-stop [^Engine engine]
  (when (= @(.status engine) :running)
    (reset! (.status engine) :stopped)))

(defn engine-clear 
  [^Engine engine]
  (if (= @(.status engine) :running)
    (reset! (.clear engine) true)))
                            
(defn engine-status 
  [^Engine engine]
  @(.status engine))

(defn engine-kill-all
  "Kills all engines and clears them"
  []
  (loop [[a & b] (drain-atom! engines)]
    (when a
      (engine-clear a)
      (engine-stop a)
      (recur b)
      )))

(defn engines-clear
  "Kills all engines and clears global engines list. Useful for development in REPL, but user must be 
  careful after clearing not to use existing engines."
  []
  (engine-kill-all)
  (reset! engines []))


;; Non-Realtime Engine functions

(defn engine->disk 
  "Runs engine and writes output to disk.  This will run the engine until all
  audio functions added to it are complete.  

  Warning: Be careful not to render with an engine setup with infinite
  duration!"
  [^Engine engine ^String filename]
  (let [baos (ByteArrayOutputStream.)
        buf (ByteBuffer/allocate (.byte-buffer-size engine))
        out-buffer (double-array (.out-buffer-size engine))
        pending-afuncs (.pending-afuncs engine)
        pending-pre-cfuncs (.pending-pre-cfuncs engine)
        pending-post-cfuncs (.pending-post-cfuncs engine)
        start-time (System/currentTimeMillis)
        sr (.sample-rate engine)
        buffer-size (.buffer-size engine)
        nchnls (.nchnls engine)
        run-engine-events (event-list-processor (.event-list engine))]
    (loop [pre-cfuncs (drain-atom! pending-pre-cfuncs)
           cur-funcs (drain-atom! pending-afuncs) 
           post-cfuncs (drain-atom! pending-post-cfuncs)
           buffer-count 0]
      (let [more-engine-events
            (binding [*current-buffer-num* buffer-count 
                      *sr* sr 
                      *buffer-size* buffer-size 
                      *nchnls* nchnls]
              (run-engine-events)) 
            pre (binding [*current-buffer-num* buffer-count 
                          *sr* sr 
                          *buffer-size* buffer-size 
                          *nchnls* nchnls] 
                  (process-cfuncs (concat-drain! pre-cfuncs pending-pre-cfuncs)))
            afs (binding [*current-buffer-num* buffer-count 
                          *sr* sr 
                          *buffer-size* buffer-size 
                          *nchnls* nchnls]
                  (process-buffer (concat-drain! cur-funcs pending-afuncs) out-buffer buf))
            post (binding [*current-buffer-num* buffer-count 
                           *sr* sr 
                           *buffer-size* buffer-size 
                           *nchnls* nchnls]
                   (process-cfuncs (concat-drain! post-cfuncs pending-post-cfuncs)))
            ]  
        (if (or (not-empty pre) (not-empty afs) (not-empty post) more-engine-events)  
          (do 
            (.write baos (.array buf))
            (.clear buf)
            (recur pre afs post (unchecked-inc buffer-count)))
          (let [data (.toByteArray baos)
                bais (ByteArrayInputStream. data)
                af (AudioFormat. (.sample-rate engine) 16 (.nchnls engine) true true)
                aftype AudioFileFormat$Type/WAVE 
                ais (AudioInputStream. bais af (alength data))
                f (File. filename)]
            (println "Writing output to " (.getAbsolutePath f))
            (AudioSystem/write ais aftype f)
            (println "Elapsed time: " 
                     (/ (- (System/currentTimeMillis) start-time) 1000.0)))
          )))))
 
;; Event functions dealing with audio engines

(defn fire-engine-event 
  "create an instance of an audio function and adds to the engine" 
  [eng evt]  
  (when-let [afunc (fire-event evt)] 
    (engine-add-afunc eng afunc)))

(defn wrap-engine-event 
  [eng ^Event evt]
  (wrap-event fire-engine-event [eng] evt))

(defn engine-events 
  "Takes an engine and series of events, wrapping the events as engine-events.
  If single arg given, assumes it is a list of events."
  ([eng args]
   (if (sequential? args)
     (map #(wrap-engine-event eng %) args)    
     (map #(wrap-engine-event eng %) [args])))
  ([eng x & args]
   (engine-events eng (list* x args))))

;; Utility Functions

(defn run-audio-block 
  "TODO: Fix this function and document..."
  [a-block & {:keys [sample-rate nchnls block-size] 
              :or {sample-rate 44100 nchnls 1 block-size 64}}]
  ;(let [af (AudioFormat. sample-rate 16 nchnls true true)
  ;      #^SourceDataLine line (open-line af) 
  ;      buffer (ByteBuffer/allocate buffer-size)
  ;      write-buffer-size (/ buffer-size 2)
  ;      frames (quot write-buffer-size *buffer-size*)]
  ;  (loop [x 0]
  ;    (if (< x frames)
  ;      (if-let [buf ^doubles (a-block)]
  ;        (do
  ;          (doubles->byte-buffer buf buffer)
  ;          (recur (unchecked-inc x)))
  ;        (do
  ;          (.write line (.array buffer) 0 buffer-size)
  ;          (.clear buffer)))
  ;      (do
  ;        (.write line (.array buffer) 0 buffer-size)
  ;        (.clear buffer)
  ;        (recur 0))))
  ;  (.flush line)
  ;  (.close line))
  
  )

