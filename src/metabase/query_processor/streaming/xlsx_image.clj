(ns metabase.query-processor.streaming.xlsx-image
  "Utilities for embedding images in XLSX exports."
  (:require
   [clj-http.client :as http]
   [clojure.string :as str]
   [metabase.util :as u]
   [metabase.util.http :as util.http]
   [metabase.util.log :as log])
  (:import
   (org.apache.poi.ss.usermodel ClientAnchor$AnchorType Workbook)
   (org.apache.poi.xssf.streaming SXSSFSheet)
   (org.apache.poi.xssf.usermodel XSSFClientAnchor)))

(set! *warn-on-reflection* true)

(def ^:private max-image-size-bytes
  "Maximum size for downloaded images (5MB)"
  (* 5 1024 1024))

(def ^:private max-total-image-bytes
  "Maximum total image bytes embedded in one export (50MB). This is a per-export limit chosen by convention."
  (* 50 1024 1024))

(def ^:private max-download-attempts
  "Maximum image download attempts per export (1000). This is a per-export limit chosen by convention."
  1000)

(def ^:private download-timeout-ms
  "Timeout for image downloads (10 seconds)"
  10000)

(def ^:private supported-extensions
  "Image formats supported by Apache POI"
  #{".png" ".jpg" ".jpeg" ".bmp"})

(def ^:private download-buffer-size
  "Size of the buffer used while reading image downloads."
  8192)

(defn new-embed-state
  "Returns a fresh per-export state used to budget and deduplicate embedded images."
  []
  (atom {:bytes-used 0
         :attempts   0
         :cache      {}}))

(defn supported-image-format?
  "Returns true if the URL appears to point to a supported image format."
  [url]
  (when url
    (let [url-lower (u/lower-case-en (str url))]
      (some #(str/ends-with? url-lower %) supported-extensions))))

(defn- extract-format
  "Extracts the image format from a URL."
  [url]
  (let [url-lower (u/lower-case-en (str url))
        extension (last (str/split url-lower #"\."))]
    (case extension
      ("jpg" "jpeg") "jpeg"
      "png" "png"
      "bmp" "bmp"
      "png"))) ;; default to PNG

(defn- image-format->poi-type
  "Maps an image format string to the corresponding Apache POI picture type constant."
  [format-str]
  (case (u/lower-case-en format-str)
    "png" Workbook/PICTURE_TYPE_PNG
    ("jpg" "jpeg") Workbook/PICTURE_TYPE_JPEG
    "bmp" Workbook/PICTURE_TYPE_DIB
    Workbook/PICTURE_TYPE_PNG)) ;; default

(defn- read-image-body
  "Reads at most `max-image-size-bytes` from the body stream, throwing if it is larger. Closes the stream."
  ^bytes [^java.io.InputStream in]
  (with-open [^java.io.InputStream body in]
    (let [^java.io.ByteArrayOutputStream out (java.io.ByteArrayOutputStream.)
          ^bytes                            buffer (byte-array download-buffer-size)]
      (loop [total 0]
        (let [limit (int (min download-buffer-size (inc (- max-image-size-bytes total))))
              n     (.read body buffer 0 limit)]
          (if (neg? n)
            (.toByteArray out)
            (let [total' (+ total n)]
              (when (> total' max-image-size-bytes)
                (throw (ex-info "Image too large" {:size total'})))
              (.write out buffer 0 n)
              (recur total'))))))))

(defn download-image
  "Downloads an image from the given URL with security checks and size limits. Redirects are not followed.
   Returns a map with :bytes and :format keys on success, nil on failure.

   Parameters:
   - url: Image URL to download"
  [url]
  (try
    ;; Security validation
    (when-not (util.http/host-allowed-for-network-policy? :external-only url)
      (log/warnf "Image URL failed security validation: %s" url)
      (throw (ex-info "Invalid host" {:url url})))

    ;; Download the image
    (let [response (http/get url {:as                 :stream
                                  :redirect-strategy  :none
                                  :socket-timeout     download-timeout-ms
                                  :connection-timeout download-timeout-ms
                                  :throw-exceptions   false
                                  :dns-resolver       (util.http/network-policy-dns-resolver :external-only)})
          status   (:status response)
          ^java.io.InputStream body (:body response)]
      (when-not (= 200 status)
        (when body
          (.close body))
        (throw (ex-info "HTTP error" {:status status :url url})))

      (when-not body
        (throw (ex-info "Missing response body" {:url url})))

      {:bytes  (read-image-body body)
       :format (extract-format url)})

    (catch Exception e
      (log/debugf e "Failed to download image: %s" url)
      nil)))

(defn- add-picture!
  "Adds an anchored picture to the sheet using an existing workbook picture index."
  [sheet row-num col-num picture-index]
  (let [drawing (or (.getDrawingPatriarch ^SXSSFSheet sheet)
                    (.createDrawingPatriarch ^SXSSFSheet sheet))
        ;; The image will be positioned in the cell and sized to fit
        anchor  (doto (XSSFClientAnchor.)
                  (.setCol1 col-num)
                  (.setRow1 row-num)
                  (.setCol2 (inc col-num))
                  (.setRow2 (inc row-num))
                  (.setAnchorType ClientAnchor$AnchorType/MOVE_AND_RESIZE))]
    (.createPicture drawing anchor picture-index)
    true))

(defn- limit-reached?
  [{:keys [bytes-used attempts budget-exhausted?]}]
  (or budget-exhausted?
      (>= attempts max-download-attempts)
      (>= bytes-used max-total-image-bytes)))

(defn- log-limit-once!
  [state]
  (when-not (:limit-logged? @state)
    (swap! state assoc :limit-logged? true)
    (log/info "XLSX image embedding budget exhausted; exporting remaining images as URLs.")))

(defn embed-image!
  "Attempts to embed an image from URL into the specified Excel cell.
   Returns true if successful, false if fallback to URL text is needed.

   Parameters:
   - sheet: SXSSFSheet where the image should be embedded
   - workbook: SXSSFWorkbook containing the sheet
   - row-num: Row index (0-based)
   - col-num: Column index (0-based)
   - url: Image URL string
   - state: atom from [[new-embed-state]] for this export"
  [sheet workbook row-num col-num url state]
  (try
    (let [{:keys [cache] :as snapshot} @state]
      (cond
        (not (supported-image-format? url))
        (do
          (log/debugf "Unsupported image format for URL: %s" url)
          false)

        (= :failed (get cache url))
        false

        (contains? cache url)
        (add-picture! sheet row-num col-num (get cache url))

        (limit-reached? snapshot)
        (do
          (log-limit-once! state)
          false)

        :else
        (let [_          (swap! state update :attempts inc)
              image-data (download-image url)]
          (if-not image-data
            (do
              (swap! state assoc-in [:cache url] :failed)
              false)
            (let [^bytes image-bytes (:bytes image-data)
                  image-size        (alength image-bytes)
                  format            (:format image-data)]
              (if (> (+ (:bytes-used @state) image-size) max-total-image-bytes)
                (do
                  (swap! state #(-> %
                                    (assoc :budget-exhausted? true)
                                    (assoc-in [:cache url] :failed)))
                  (log-limit-once! state)
                  false)
                (let [picture-index (.addPicture workbook image-bytes (image-format->poi-type format))]
                  (swap! state #(-> %
                                    (update :bytes-used + image-size)
                                    (assoc-in [:cache url] picture-index)))
                  (add-picture! sheet row-num col-num picture-index))))))))

    (catch Exception e
      (log/warnf e "Failed to embed image from URL: %s" url)
      false)))
