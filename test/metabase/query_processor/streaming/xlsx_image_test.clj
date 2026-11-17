(ns metabase.query-processor.streaming.xlsx-image-test
  (:require
   [clj-http.client]
   [clojure.test :refer :all]
   [metabase.query-processor.streaming.xlsx-image :as xlsx-image]
   [metabase.util.http])
  (:import
   (java.io ByteArrayInputStream)
   (java.util Base64)
   (org.apache.poi.xssf.streaming SXSSFSheet SXSSFWorkbook)
   (org.apache.poi.xssf.usermodel XSSFDrawing)))

(def ^:private png-bytes
  (.decode (Base64/getDecoder)
           "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="))

(defn- with-test-workbook
  [f]
  (with-open [workbook (SXSSFWorkbook.)]
    (f workbook (.createSheet workbook "Test"))))

(defn- sheet-picture-count
  [^SXSSFSheet sheet]
  (if-let [drawing (.getDrawingPatriarch sheet)]
    (count (.getShapes ^XSSFDrawing drawing))
    0))

(defn- workbook-picture-count
  [^SXSSFWorkbook workbook]
  (count (.getAllPictures workbook)))

(defn- tracking-stream
  "Returns a [stream closed?] pair where closed? flips when the stream is closed."
  [bytes]
  (let [closed? (atom false)
        stream  (proxy [ByteArrayInputStream] [bytes]
                  (close []
                    (reset! closed? true)))]
    [stream closed?]))

(deftest supported-image-format-test
  (testing "Supported image formats"
    (is (xlsx-image/supported-image-format? "http://example.com/image.png"))
    (is (xlsx-image/supported-image-format? "http://example.com/image.jpg"))
    (is (xlsx-image/supported-image-format? "http://example.com/image.jpeg"))
    (is (xlsx-image/supported-image-format? "http://example.com/image.bmp"))
    (is (xlsx-image/supported-image-format? "http://example.com/image.PNG"))
    (is (xlsx-image/supported-image-format? "http://example.com/image.JPG")))

  (testing "Unsupported image formats"
    (is (not (xlsx-image/supported-image-format? "http://example.com/image.svg")))
    (is (not (xlsx-image/supported-image-format? "http://example.com/image.webp")))
    (is (not (xlsx-image/supported-image-format? "http://example.com/document.pdf")))
    (is (not (xlsx-image/supported-image-format? "http://example.com/page.html"))))

  (testing "Nil and invalid inputs"
    (is (not (xlsx-image/supported-image-format? nil)))
    (is (not (xlsx-image/supported-image-format? "")))))

(deftest download-image-test
  (testing "Download image with mocked HTTP"
    (with-redefs [clj-http.client/get (fn [_url _opts]
                                        {:status 200
                                         :body (ByteArrayInputStream. (byte-array [1 2 3 4]))})
                  metabase.util.http/host-allowed-for-network-policy? (constantly true)]
      (let [result (xlsx-image/download-image "http://example.com/test.png")]
        (is (some? result))
        (is (= "png" (:format result)))
        (is (= 4 (count (:bytes result)))))))

  (testing "Download failure on HTTP error"
    (with-redefs [clj-http.client/get (fn [_url _opts]
                                        {:status 404
                                         :body nil})
                  metabase.util.http/host-allowed-for-network-policy? (constantly true)]
      (let [result (xlsx-image/download-image "http://example.com/notfound.png")]
        (is (nil? result)))))

  (testing "Download failure on security check"
    (with-redefs [metabase.util.http/host-allowed-for-network-policy? (constantly false)]
      (let [result (xlsx-image/download-image "http://internal.server/image.png")]
        (is (nil? result))))))

(deftest download-image-security-test
  (testing "HTTP options disable redirects and use the network-policy DNS resolver"
    (let [opts (atom nil)]
      (with-redefs [clj-http.client/get (fn [_url request-opts]
                                          (reset! opts request-opts)
                                          {:status 200
                                           :body (ByteArrayInputStream. (byte-array [1 2 3 4]))})
                    metabase.util.http/host-allowed-for-network-policy? (constantly true)]
        (is (some? (xlsx-image/download-image "http://example.com/test.png")))
        (is (= :stream (:as @opts)))
        (is (= :none (:redirect-strategy @opts)))
        (is (some? (:dns-resolver @opts))))))

  (testing "Redirect responses are not followed"
    (with-redefs [clj-http.client/get (fn [_url _opts]
                                        {:status 302
                                         :body (ByteArrayInputStream. (byte-array 0))})
                  metabase.util.http/host-allowed-for-network-policy? (constantly true)]
      (is (nil? (xlsx-image/download-image "http://example.com/redirect.png"))))))

(deftest download-image-size-limit-test
  (let [max-size @#'xlsx-image/max-image-size-bytes]
    (testing "Bodies at the size limit are accepted"
      (with-redefs [clj-http.client/get (fn [_url _opts]
                                          {:status 200
                                           :body (ByteArrayInputStream. (byte-array max-size))})
                    metabase.util.http/host-allowed-for-network-policy? (constantly true)]
        (let [result (xlsx-image/download-image "http://example.com/large.png")]
          (is (some? result))
          (is (= max-size (count (:bytes result)))))))

    (testing "Bodies over the size limit are rejected"
      (with-redefs [clj-http.client/get (fn [_url _opts]
                                          {:status 200
                                           :body (ByteArrayInputStream. (byte-array (inc max-size)))})
                    metabase.util.http/host-allowed-for-network-policy? (constantly true)]
        (is (nil? (xlsx-image/download-image "http://example.com/too-large.png")))))))

(deftest download-image-closes-body-test
  (let [max-size @#'xlsx-image/max-image-size-bytes]
    (testing "The body stream is closed on success"
      (let [[body closed?] (tracking-stream (byte-array [1 2 3 4]))]
        (with-redefs [clj-http.client/get (fn [_url _opts]
                                            {:status 200
                                             :body body})
                      metabase.util.http/host-allowed-for-network-policy? (constantly true)]
          (is (some? (xlsx-image/download-image "http://example.com/test.png")))
          (is (true? @closed?)))))

    (testing "The body stream is closed when the image is too large"
      (let [[body closed?] (tracking-stream (byte-array (inc max-size)))]
        (with-redefs [clj-http.client/get (fn [_url _opts]
                                            {:status 200
                                             :body body})
                      metabase.util.http/host-allowed-for-network-policy? (constantly true)]
          (is (nil? (xlsx-image/download-image "http://example.com/too-large.png")))
          (is (true? @closed?)))))

    (testing "The body stream is closed on a non-200 response"
      (let [[body closed?] (tracking-stream (byte-array [1 2 3 4]))]
        (with-redefs [clj-http.client/get (fn [_url _opts]
                                            {:status 302
                                             :body body})
                      metabase.util.http/host-allowed-for-network-policy? (constantly true)]
          (is (nil? (xlsx-image/download-image "http://example.com/redirect.png")))
          (is (true? @closed?)))))))


(deftest embed-image-cache-test
  (with-test-workbook
    (fn [workbook sheet]
      (let [state (xlsx-image/new-embed-state)
            url   "http://example.com/image.png"
            calls (atom 0)]
        (with-redefs [xlsx-image/download-image (fn [_url]
                                                  (swap! calls inc)
                                                  {:bytes  png-bytes
                                                   :format "png"})]
          (is (true? (xlsx-image/embed-image! sheet workbook 0 0 url state)))
          (is (true? (xlsx-image/embed-image! sheet workbook 1 0 url state))))
        (is (= 1 @calls))
        (is (= 2 (sheet-picture-count sheet)))
        (is (= 1 (workbook-picture-count workbook)))
        (is (contains? (:cache @state) url))))))

(deftest embed-image-failed-cache-test
  (with-test-workbook
    (fn [workbook sheet]
      (let [state (xlsx-image/new-embed-state)
            url   "http://example.com/missing.png"
            calls (atom 0)]
        (with-redefs [xlsx-image/download-image (fn [_url]
                                                  (swap! calls inc)
                                                  nil)]
          (is (false? (xlsx-image/embed-image! sheet workbook 0 0 url state)))
          (is (false? (xlsx-image/embed-image! sheet workbook 1 0 url state))))
        (is (= 1 @calls))
        (is (= :failed (get-in @state [:cache url])))
        (is (zero? (sheet-picture-count sheet)))
        (is (zero? (workbook-picture-count workbook)))))))

(deftest embed-image-attempt-limit-test
  (with-test-workbook
    (fn [workbook sheet]
      (let [state (xlsx-image/new-embed-state)
            url   "http://example.com/image.png"
            calls (atom 0)]
        (swap! state assoc :attempts @#'xlsx-image/max-download-attempts)
        (with-redefs [xlsx-image/download-image (fn [_url]
                                                  (swap! calls inc)
                                                  {:bytes  png-bytes
                                                   :format "png"})]
          (is (false? (xlsx-image/embed-image! sheet workbook 0 0 url state))))
        (is (zero? @calls))
        (is (zero? (sheet-picture-count sheet)))
        (is (zero? (workbook-picture-count workbook)))
        (is (:limit-logged? @state))))))

(deftest embed-image-byte-budget-test
  (with-test-workbook
    (fn [workbook sheet]
      (let [state      (xlsx-image/new-embed-state)
            max-size   @#'xlsx-image/max-total-image-bytes
            first-url  "http://example.com/first.png"
            second-url "http://example.com/second.png"
            calls      (atom 0)]
        (swap! state assoc :bytes-used (- max-size 3))
        (with-redefs [xlsx-image/download-image (fn [_url]
                                                  (swap! calls inc)
                                                  {:bytes  (byte-array 4)
                                                   :format "png"})]
          (is (false? (xlsx-image/embed-image! sheet workbook 0 0 first-url state)))
          (is (false? (xlsx-image/embed-image! sheet workbook 1 0 second-url state))))
        (is (= 1 @calls))
        (is (zero? (sheet-picture-count sheet)))
        (is (zero? (workbook-picture-count workbook)))
        (is (:budget-exhausted? @state))
        (is (:limit-logged? @state))))))

(deftest embed-image-cache-after-budget-test
  (with-test-workbook
    (fn [workbook sheet]
      (let [state (xlsx-image/new-embed-state)
            url   "http://example.com/image.png"
            calls (atom 0)]
        (with-redefs [xlsx-image/download-image (fn [_url]
                                                  (swap! calls inc)
                                                  {:bytes  png-bytes
                                                   :format "png"})]
          (is (true? (xlsx-image/embed-image! sheet workbook 0 0 url state)))
          (swap! state assoc :budget-exhausted? true)
          (is (true? (xlsx-image/embed-image! sheet workbook 1 0 url state))))
        (is (= 1 @calls))
        (is (= 2 (sheet-picture-count sheet)))
        (is (= 1 (workbook-picture-count workbook)))))))
