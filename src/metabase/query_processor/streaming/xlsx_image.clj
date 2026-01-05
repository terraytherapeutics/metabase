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

(def ^:private download-timeout-ms
  "Timeout for image downloads (10 seconds)"
  10000)

(def ^:private supported-extensions
  "Image formats supported by Apache POI"
  #{".png" ".jpg" ".jpeg" ".bmp"})

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

(defn download-image
  "Downloads an image from the given URL with security checks and size limits.
   Returns a map with :bytes and :format keys on success, nil on failure.

   Parameters:
   - url: Image URL to download"
  [url]
  (try
    ;; Security validation
    (when-not (util.http/valid-host? :external-only url)
      (log/warnf "Image URL failed security validation: %s" url)
      (throw (ex-info "Invalid host" {:url url})))

    ;; Download the image
    (let [response (http/get url {:as :byte-array
                                  :socket-timeout download-timeout-ms
                                  :connection-timeout download-timeout-ms
                                  :throw-exceptions false})
          status (:status response)]

      (when-not (= 200 status)
        (throw (ex-info "HTTP error" {:status status :url url})))

      (let [bytes (:body response)
            content-length (count bytes)]

        (when (> content-length max-image-size-bytes)
          (throw (ex-info "Image too large" {:size content-length :url url})))

        {:bytes bytes
         :format (extract-format url)}))

    (catch Exception e
      (log/debugf e "Failed to download image: %s" url)
      nil)))

(defn embed-image!
  "Attempts to embed an image from URL into the specified Excel cell.
   Returns true if successful, false if fallback to URL text is needed.

   Parameters:
   - sheet: SXSSFSheet where the image should be embedded
   - workbook: SXSSFWorkbook containing the sheet
   - row-num: Row index (0-based)
   - col-num: Column index (0-based)
   - url: Image URL string"
  [sheet workbook row-num col-num url]
  (try
    (when-not (supported-image-format? url)
      (log/debugf "Unsupported image format for URL: %s" url)
      (throw (ex-info "Unsupported format" {:url url})))

    ;; Download image
    (let [image-data (download-image url)]

      (when-not image-data
        (throw (ex-info "Failed to download image" {:url url})))

      (let [{:keys [bytes format]} image-data
            poi-type (image-format->poi-type format)
            picture-index (.addPicture workbook bytes poi-type)

            ;; Get or create drawing patriarch
            drawing (or (.getDrawingPatriarch ^SXSSFSheet sheet)
                        (.createDrawingPatriarch ^SXSSFSheet sheet))

            ;; Create anchor for positioning
            ;; The image will be positioned in the cell and sized to fit
            anchor (doto (XSSFClientAnchor.)
                     (.setCol1 col-num)
                     (.setRow1 row-num)
                     (.setCol2 (inc col-num))
                     (.setRow2 (inc row-num))
                     (.setAnchorType ClientAnchor$AnchorType/MOVE_AND_RESIZE))]

        ;; Create the picture - it will be sized according to anchor bounds (MOVE_AND_RESIZE anchor type)
        (.createPicture drawing anchor picture-index)
        true))

    (catch Exception e
      (log/warnf e "Failed to embed image from URL: %s" url)
      false)))
