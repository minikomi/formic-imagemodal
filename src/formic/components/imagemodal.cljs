(ns formic.components.imagemodal
  (:require [ajax.core :refer [GET POST]]
            [cljsjs.dropzone]
            [reagent.core :as r]
            [formic.util :as futil]
            [formic.components.inputs :as inputs]
            [goog.events :as events]
            [goog.events.EventType :as event-type]
            [goog.dom.classes :as gclass]
            [formic.field :as field]
            [goog.events.KeyCodes :as key-codes]
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
             :params (:dz-params options)}
            dz (js/Dropzone. "#upload" (clj->js dropzone-options))]
        (.on dz "sending" (fn [ev]
                            (reset! panel-state :sending)))
        (.on dz "success" (fn [ev resp]
                            (reset! panel-state :select)
                            (when-let [f (:on-success options)]
                              (f value (js->clj resp
                                                :keywordize-keys true)))))))
    :reagent-render
    (fn [panel-state f]
      [:div.dropzone.needsclick.dz-clickable
       [:div#upload
        [:div.dz-message
         (get-in options [:messages :drop-or-click] "Drop or click to upload")]]])}))

;; Select panel

(defn panel-paging [state options get-images-fn]
  [:ul.formic-image-modal-paging
   [:li.formic-image-modal-prev
    (when (< 0 (:current-page @state 0))
      [:a.button
       {:class (get-in options [:classes :page-button])
        :href "#"
        :on-click (fn [ev]
                    (.preventDefault ev)
                    (swap! state update-in [:current-page] dec)
                    (get-images-fn))} "<"])]
   [:li.formic-image-modal-page-number
    [:h5
     {:class (get-in options [:classes :modal-panel-title])}
     "Page " (-> @state :current-page inc)]]
   [:li.formic-image-modal-next
    (when (:next-page @state)
      [:a.button {:class (get-in options [:classes :page-button])
                  :href "#"
                  :on-click (fn [ev]
                              (.preventDefault ev)
                              (swap! state update-in [:current-page] inc)
                              (get-images-fn))} ">"])]])

(defn default-img->title [options img]
  (-> img
      ((:image->thumbnail options))
      (str/split #"^.*?([^\\\/]*)$")
      last))

(defn loaded-panel [state options get-images-fn value touched panel-state]
  [:div
   (when (and (:paging options)
              (or (:next-page @state)
                  (< 0 (:current-page @state 0))))
     [panel-paging state options get-images-fn])
   [:ul.formic-image-modal-grid
    {:class (get-in options [:classes :image-grid])}
    (doall
     (for [i (:current-images @state)
           :let [thumb-src ((or (:image->thumbnail options) identity) i)
                 current-src (when @value
                               ((or (:image->thumbnail options) identity)
                                @value))
                 selected (= i @value)
                 img-title
                 (if-let [f (:image->title options)]
                   (f i)
                   (default-img->title options i))]]
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
           :src thumb-src}]
         (when img-title
          [:h5
           {:class (get-in options [:classes :image-grid-filename])}
           img-title
           ])]]))]])

(defn error-panel [get-images-fn classes]
  [:div.error
   {:class (:error classes)}
   [:h4
    {:class (:error-txt classes)}
    "Loading Error."]
   [:button.retry-button
    {:class (:retry-button classes)
     :on-click (fn [ev]
                 (.preventDefault ev)
                 (get-images-fn))}
    "Retry"]])

(defn panel-search [state options get-images-fn]
  [:div.formic-image-modal-search
   [:input {:type "text"
            :value (:search-str @state)
            :class (get-in options [:classes :search-input])
            :on-key-down
            (fn [ev]
              (when (= ev.keyCode 13)
                (.stopPropagation ev)
                (.preventDefault ev)
                (swap! state assoc :current-page 0)
                (get-images-fn)
                false))
            :on-change (fn [ev]
                         (swap! state
                                assoc
                                :search-str (.. ev -target -value)))}]
   [:a
    {:class (get-in options [:classes :search-button])
     :href "#"
     :on-click (fn [ev]
                 (.preventDefault ev)
                 (swap! state assoc :current-page 0)
                 (get-images-fn))}
    "Search"]])

(defn default-error-handler [state]
  (fn [_]
   (swap! state assoc
          :current-images nil
          :mode :error)))

(defn default-list-images-fn [endpoints state]
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
        (default-error-handler state)
        }))

(defn select-panel [panel-state {:keys [value touched err options]}]
  (let [{:keys [endpoints]} options
        state (r/atom {:current-page nil
                       :current-images nil
                       :search-str (:initial-search-str options)
                       :mode :loading})
        close-modal-fn (fn [ev]
                         (.preventDefault ev)
                         (reset! panel-state :closed))
        get-images-fn (fn []
                        (if-let [f (:list-images-fn options)]
                          (f endpoints state)
                          (default-list-images-fn endpoints state)))
        esc-fn (fn [ev]
                 (case ev.keyCode
                   27
                   (close-modal-fn ev)
                   true))
        classes (:classes options)]
    (r/create-class
     {:display-name "select panel"
      :component-will-mount
      (fn [_]
        (get-images-fn)
        (futil/set-body-class "formic-image-modal-open" true)
        (events/listen js/window
                       event-type/KEYDOWN
                       esc-fn))
      :component-will-unmount
      (fn [_]
        (futil/set-body-class "formic-image-modal-open" false)
        (events/unlisten js/window
                         event-type/KEYDOWN
                         esc-fn))
      :reagent-render
      (fn [panel-state f]
        [:div.formic-image-modal-panel
         {:class (get-in options [:classes :modal-panel])}
         [:a.formic-image-modal-close
          {:href "#"
           :on-click close-modal-fn}
          "X"]
         (if @(:value f)
           [:a.formic-image-modal-current
            {:class (get-in options [:classes :select-image-current])
             :href "#"
             :on-click close-modal-fn}
            [:span (or (get-in options [:messages :current])
                       "Current Image") ":"]
            [:img.formic-image-current
             {:class (get-in options [:classes :image-current])
              :src ((or
                     (:image->src options)
                     (:image->thumbnail options)
                     identity)
                    @(:value f))}]]
           [:h4 (or (get-in options [:messages :not-selected]) "Not Selected")])
         [:div.formic-image-modal-panel-inner
          {:class (get-in options [:classes :modal-panel-inner])}
          (when (:search options)
            [panel-search state options get-images-fn])]
         (case (:mode @state)
           :loaded
           [loaded-panel state options get-images-fn value touched panel-state]
           :error
           [error-panel get-images-fn classes]
           :loading
           [:h4
            {:class (get-in options [:classes :loading])}
            "Loading"])])})))

(defn panel-select-tabs [panel-state f]
  (let [classes (get-in f [:classes :select])]
   [:div
    [:ul.formic-image-modal-panel-select-tabs
     [:li
      {:class (inputs/add-cls
               (:active classes)
               (when (= @panel-state :select) "active"))}
      [:a {:href "#"
           :on-click (fn [ev]
                       (.preventDefault ev)
                       (when
                           (not= @panel-state :sending)
                           (reset! panel-state :select)))}
       (get-in f [:options :messages :select]
               "SELECT")]]

     (when (boolean (get-in f [:options :endpoints :upload]))
       [:li
        {:class (when (or
                       (= @panel-state :sending)
                       (= @panel-state :upload)) "active")}
        [:a {:href "#"
             :on-click (fn [ev]
                         (.preventDefault ev)
                         (reset! panel-state :upload))}
         (get-in f [:options :messages :upload]
               "UPLOAD")]])]]))

(defn image-modal [panel-state f]
  (let [el (atom nil)
        classes (get-in f [:classes :image-modal])
        on-click-outside
        (fn [ev]
          (when (and
                 (not= @panel-state :sending)
                 (not= ev.target @el)
                 (not (.contains @el ev.target))
                 (not (gclass/has ev.target "dz-hidden-input")))
            (js/console.log
             "click outside"
             @el
             ev.target)
            (reset! panel-state :closed)))]
    (r/create-class
     {:component-will-mount
      #(events/listen js/window
                      event-type/CLICK
                      on-click-outside)
      :component-will-unmount
      #(events/unlisten js/window
                        event-type/CLICK
                        on-click-outside)
      :reagent-render
      (fn [panel-state f]
        [:div.formic-image-modal
         {:classes (:wrapper classes)}
         [:div.formic-image-modal-inner
          {:class (:inner classes)
           :ref #(reset! el %)}
          [panel-select-tabs panel-state f]
          (case @panel-state
            :select [select-panel panel-state f]
            (:sending :upload)
            (if (get-in f [:options :endpoints :get-signed])
              [:span "TODO"]
              ;;[s3-upload-panel panel-state f]
              [upload-panel panel-state f]))]])})))

(defn image-field [{:keys [id err options classes] :as f}]
  (let [panel-state (r/atom :closed)
        modal-open-classes (:modal-open classes)
        {:keys [image->src
                image->thumbnail]} options]
    (fn [f]
      [inputs/common-wrapper f
       [:div.formic-image-field
        [:a.formic-image-open-modal.button
         {:href "#"
          :class (:wrapper modal-open-classes)
          :on-click (fn [ev]
                      (.preventDefault ev)
                      (reset! panel-state :select))}
         (if @(:value f)
           [:img.formic-image-current
            {:class (:image-current modal-open-classes)
             :src ((or
                    (:image->src options)
                    (:image->thumbnail options)
                    identity)
                   @(:value f))}]
           [:h4.formic-image-not-selected
            {:class (:not-selected modal-open-classes)}
            (or (get-in options [:messages :not-selected] "Not Selected"))])
         [:span.formic-image-open-modal-label-wrapper
          [:span.formic-image-open-modal-label
           {:class (:text modal-open-classes)}
           (get-in options [:messages :select] "SELECT")]]]
        (when (not= :closed @panel-state)
          [image-modal panel-state f])]])))

(field/register-component
 :formic-imagemodal
 {:component image-field})
