(ns metabase.query-processor.streaming.xlsx-image-test
  (:require
   [clj-http.client]
   [clojure.test :refer :all]
   [metabase.query-processor.streaming.xlsx-image :as xlsx-image]
   [metabase.util.http])
  (:import
   (java.io ByteArrayInputStream)))

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
