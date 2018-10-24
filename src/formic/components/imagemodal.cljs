(ns formic.components.imagemodal
  (:require [ajax.core :refer [GET POST]]
            [cljsjs.dropzone]
            [reagent.core :as r]
            [formic.util :as u]
            [formic.field :as field]
            [clojure.string :as str]))

;; required endpoints
;;  - upload
;;  - list
;;    -- optional page var
;;    -- optional search-str var

(defn assoc-if
  "assoc key/value pairs to the map only on non-nil values

   (assoc-if {} :a 1)
   => {:a 1}

   (assoc-if {} :a 1 :b nil)
   => {:a 1}"
  ([m k v]
     (if (not (nil? v)) (assoc m k v) m))
  ([m k v & more]
     (apply assoc-if (assoc-if m k v) more)))

;; Server Upload
;; -------------------------------------------------------------------------------

(defn upload-panel [panel-state {:keys [value err options]}]
  (r/create-class
   {:component-did-mount
    (fn [this]
      (let [{:keys [data endpoints]} options
            dropzone-options
            {:url (:upload endpoints)
             :headers {"X-CSRF-Token"
                       (.-value (.getElementById
                                 js/document
                                 "__anti-forgery-token"))}
             :data data}
            dz (js/Dropzone. "#upload" (clj->js dropzone-options))]
        (.on dz "sending" (fn [ev]
                            (reset! panel-state :sending)))
        (.on dz "complete" (fn [ev]
                             (reset! panel-state :select)))
        ))
    :reagent-render
    (fn [panel-state f]
      [:div.dropzone.needsclick.dz-clickable
       [:div#upload
        [:div.dz-message "Drop or click to upload"]]])}))

;; S3 direct upload -- TODO
;; -------------------------------------------------------------------------------

;;(defn sign-and-send [dz sign-url file done]
;;  (when @dz
;;    (GET sign-url
;;         {:params {:file-path (.-name file)
;;                   :content-type (.-type file)}
;;          :handler
;;          (fn [data]
;;            (set! (.-uploadURL file) (:url data))
;;            (done)
;;            (js/setTimeout
;;             (.processFile @dz file)))
;;          :error-handler #("Failed to get an s3 signed upload url" %)})))
;;
;;(defn s3-upload-panel [panel-state f]
;;  (r/create-class
;;   {:component-did-mount
;;    (fn [this]
;;      (let [endpoints (:endpoints @f)
;;            value (r/cursor f [:value])
;;            error (r/cursor f [:error])
;;            ;; need to declare dz since it's used in options as well
;;            dz (atom nil)
;;            dropzone-options
;;            {:url "/"
;;             :method "PUT"
;;             :sending (fn [file xhr]
;;                        (let [send (.-send xhr)]
;;                          (set! (.-send xhr)
;;                                #(.call send xhr file))))
;;             :paralellUploads 1
;;             :uploadMultiple false
;;             :headers ""
;;             :dictDefaultMessage "ok"
;;             :autoProcessQueue false
;;             :accept (fn [file done]
;;                       (sign-and-send dz (:get-signed endpoints) file done))}]
;;        (reset! dz (js/Dropzone. "#upload" (clj->js dropzone-options)))
;;        (.on @dz "processing" #(set! (.. @dz -options -url) (.-uploadURL %)))
;;        (.on @dz "success" #(println %))))
;;    :reagent-render
;;    (fn [panel-state f]
;;      [:div.dropzone.needsclick.dz-clickable
;;       [:div#upload
;;        [:div.dz-message "Drop or click to upload"]]])}))

;; Select panel

(defn select-panel [panel-state {:keys [value touched err options]}]
  (println options)
  (let [{:keys [endpoints]} options
        state (r/atom {:current-page nil
                       :current-images nil
                       :search-str nil
                       :mode :loading})
        get-images (fn []
                     (swap! state assoc
                            :mode :loading
                            :current-images nil)
                     (GET (:list endpoints)
                          {:handler
                           (fn [data]
                             (swap! state assoc
                                    :current-images (:images data)
                                    :next-page (:next-page data)
                                    :prev-page (:prev-page data)
                                    :mode :loaded))
                           :params (assoc-if {}
                                     :page (:current-page @state)
                                     :search-str (:search-str @state))
                           :error-handler
                           (fn [_]
                             (swap! state assoc
                                    :current-images nil
                                    :mode :error))}))]
    (r/create-class
     {:display-name "select panel"
      :component-will-mount
      (fn [_]
        (get-images))
      :reagent-render
      (fn [panel-state f]
        [:div.modal-panel
         {:class (get-in options [:classes :modal-panel])}
         [:div.modal-panel-inner
          {:class (get-in options [:classes :modal-panel-inner])}
          [:h5
           {:class (get-in options [:classes :modal-panel-title])}
           "Page " (-> @state :current-page inc)]
          (when (:search options)
            [:div
             [:input {:type "text"
                      :value (:search-str @state)
                      :class (get-in options [:classes :search-input])
                      :on-change (fn [ev]
                                   (swap! state
                                          assoc
                                          :search-str (.. ev -target -value)))}]
             [:a.formic-image-search-button
              {:class (get-in options [:classes :search-button])
               :href "#"
               :on-click (fn [ev]
                           (.preventDefault ev)
                           (swap! state assoc :page 0)
                           (get-images))}
              "Search"]])
          (when (and (:paging options)
                     (or (:next-page @state) (:prev-page @state)))
            [:ul
             (when (:prev-page @state)
               [:li [:a.formic-image-page-button
                     {:class (get-in options [:classes :page-button])
                      :href "#"
                      :on-click (fn [ev]
                                  (.preventDefault ev)
                                  (swap! state update-in [:current-page] (:prev-page @state))
                                  (get-images))} "<"]])
             (when (:next-page @state)
               [:li [:button {:class (get-in options [:classes :page-button])
                              :href "#"
                              :on-click (fn [ev]
                                          (.preventDefault ev)
                                          (swap! state update-in [:current-page] (:next-page @state))
                                          (get-images))} ">"]])])]
         (case (:mode @state)
           :loaded
           [:ul.modal-image-grid
            {:class (get-in options [:classes :image-grid])}
            (doall
             (for [i (:current-images @state)
                   :let [thumb-src ((or (:image->thumbnail options) identity) i)
                         current-src (when @value
                                       ((or (:image->thumbnail options) identity)
                                        @value))
                         selected (= i @value)]]
               ^{:key i}
               [:li
                {:class (get-in options (if selected
                                          [:classes :image-grid-item-selected]
                                          [:classes :image-grid-item]))}
                [:a
                 {:class (get-in options [:classes :image-grid-link])
                  :on-click (fn [ev]
                              (.preventDefault ev)
                              (reset! value i)
                              (reset! touched true)
                              (reset! panel-state :closed))}
                 [:img
                  {:class (get-in options [:classes :image-grid-image])
                   :src thumb-src}]]]))]
           :error
           [:div.error
            {:class (get-in options [:classes :error])}
            [:h4
             {:class (get-in options [:classes :error-txt])}
             "Loading Error."]
            [:button.retry-button
             {:class (get-in options [:classes :retry-button])
              :on-click (fn [ev]
                          (.preventDefault ev)
                          (get-images))}
             "Retry"]]
           :loading
           [:h4
            {:class (get-in options [:classes :loading])}
            "Loading"])])})))

(defn panel-select [panel-state]
  [:ul.formic-image-modal-panel-select
   (when (not= @panel-state :sending)
     (doall
      (for [new-state [:select :upload]]
        ^{:key new-state}
        [:li
         {:class (when (= @panel-state new-state) "active") }
         [:a {:href "#"
              :on-click (fn [ev]
                          (.preventDefault ev)
                          (reset! panel-state new-state))}
          (name new-state)]])))])

(defn image-field [{:keys [id err options] :as f}]
  (let [panel-state (r/atom :closed)
        {:keys [image->src
                image->thumbnail]} options]
    (fn [f]
      [:div.formic-image-field
       {:class (when @err "error")}
       [:span.formic-input-title
        (u/format-kw id)]
       (if @(:value f)
         [:img.formic-image-current
          {:class (get-in options [:classes :image-current])
           :src ((or
                  (:image->src options)
                  (:image->thumbnail options)
                  identity)
                 @(:value f))}]
         [:h4 "Not Selected"])
       [:a.formic-image-open-modal.button
        {:on-click (fn [ev]
                     (.preventDefault ev)
                     (reset! panel-state :select))} "Select"]
       (when (not= :closed @panel-state)
         [:div.formic-image-modal
          [:div.formic-image-modal-inner
           [panel-select panel-state]
           (case @panel-state
             :select [select-panel panel-state f]
             (:sending :upload)
             (if (get-in f [:options :endpoints :get-signed])
               [:span "TODO"]
               ;;[s3-upload-panel panel-state f]
               [upload-panel panel-state f]))]])
       (when @err
         [:h3.error @err])])))

(field/register-component
 :formic-imagemodal
 {:component image-field})
