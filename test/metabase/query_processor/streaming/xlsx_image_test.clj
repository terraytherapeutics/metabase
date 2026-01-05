(ns metabase.query-processor.streaming.xlsx-image-test
  (:require
   [clj-http.client]
   [clojure.test :refer :all]
   [metabase.query-processor.streaming.xlsx-image :as xlsx-image]
   [metabase.util.http]))

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
                                         :body (byte-array [1 2 3 4])})
                  metabase.util.http/valid-host? (constantly true)]
      (let [result (xlsx-image/download-image "http://example.com/test.png")]
        (is (some? result))
        (is (= "png" (:format result)))
        (is (= 4 (count (:bytes result)))))))

  (testing "Download failure on HTTP error"
    (with-redefs [clj-http.client/get (fn [_url _opts]
                                        {:status 404
                                         :body nil})
                  metabase.util.http/valid-host? (constantly true)]
      (let [result (xlsx-image/download-image "http://example.com/notfound.png")]
        (is (nil? result)))))

  (testing "Download failure on security check"
    (with-redefs [metabase.util.http/valid-host? (constantly false)]
      (let [result (xlsx-image/download-image "http://internal.server/image.png")]
        (is (nil? result))))))
