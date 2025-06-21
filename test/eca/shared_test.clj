(ns eca.shared-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.shared :as shared]
   [eca.test-helper :as h]))

(deftest uri->filename-test
  (testing "should decode special characters in file URI"
    (is (= (h/file-path "/path+/encoded characters!")
           (shared/uri->filename (h/file-uri "file:///path%2B/encoded%20characters%21")))))
  (testing "Windows URIs"
    (is (= (when h/windows? "C:\\c.clj")
           (when h/windows? (shared/uri->filename "file:/c:/c.clj"))))
    (is (= (when h/windows? "C:\\Users\\FirstName LastName\\c.clj")
           (when h/windows? (shared/uri->filename "file:/c:/Users/FirstName%20LastName/c.clj"))))
    (is (= (when h/windows? "C:\\c.clj")
           (when h/windows? (shared/uri->filename "file:///c:/c.clj"))))))
