(ns formic.components.imagemodal
  (:require [ajax.core :refer [GET POST]]
            [cljsjs.dropzone]
            [reagent.core :as r]
            [formic.util :as u]
            [formic.components.inputs :as inputs]
            [goog.events :as events]
            [goog.events.EventType :as event-type]
            [goog.events.KeyCodes :as key-codes]
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
                             (reset! panel-state :select)))))
    :reagent-render
    (fn [panel-state f]
      [:div.dropzone.needsclick.dz-clickable
       [:div#upload
        [:div.dz-message "Drop or click to upload"]]])}))

;; Select panel

(defn panel-paging [state options get-images-fn]
  [:ul
   (when (:prev-page @state)
     [:li [:a.formic-image-page-button
           {:class (get-in options [:classes :page-button])
            :href "#"
            :on-click (fn [ev]
                        (.preventDefault ev)
                        (swap! state update-in [:current-page] dec)
                        (get-images-fn))} "<"]])
   [:li
    [:h5
     {:class (get-in options [:classes :modal-panel-title])}
     "Page " (-> @state :current-page inc)]]
   (when (:next-page @state)
     [:li
      [:a {:class (get-in options [:classes :page-button])
           :href "#"
           :on-click (fn [ev]
                       (.preventDefault ev)
                       (swap! state update-in [:current-page] inc)
                       (get-images-fn))} ">"]])])

(defn loaded-panel [state options get-images-fn value touched panel-state]
  [:div
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
           :src thumb-src}]
         [:h5
          {:class (get-in options [:classes :image-grid-filename])}
          (-> thumb-src
              (str/split #"^.*?([^\\\/]*)$")
              last)]
         ]]))]
   (when (and (:paging options)
              (or (:next-page @state) (:prev-page @state)))
     [panel-paging state options get-images-fn])])

(defn error-panel [get-images-fn classes]
  [:div.error
   {:class (classes :error)}
   [:h4
    {:class (classes :error-txt)}
    "Loading Error."]
   [:button.retry-button
    {:class (classes :retry-button)
     :on-click (fn [ev]
                 (.preventDefault ev)
                 (get-images-fn))}
    "Retry"]])

(defn panel-search [state options get-images-fn]
  [:div
   [:input {:type "text"
            :value (:search-str @state)
            :class (get-in options [:classes :search-input])
            :on-key-down
            (fn [ev]
              (when (= ev.keyCode key-codes/ENTER)
                (swap! state assoc :current-page 0)
                (get-images-fn)))
            :on-change (fn [ev]
                         (swap! state
                                assoc
                                :search-str (.. ev -target -value)))}]
   [:a.formic-image-search-button
    {:class (get-in options [:classes :search-button])
     :href "#"
     :on-click (fn [ev]
                 (.preventDefault ev)
                 (swap! state assoc :current-page 0)
                 (get-images-fn))}
    "Search"]])

(defn select-panel [panel-state {:keys [value touched err options]}]
  (let [{:keys [endpoints]} options
        state (r/atom {:current-page nil
                       :current-images nil
                       :search-str nil
                       :mode :loading})
        close-modal-fn (fn [ev]
                         (.preventDefault ev)
                         (reset! panel-state :closed))
        get-images-fn (fn []
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
                                       :mode :error))}))
        esc-fn (fn [ev]
                 (case ev.keyCode
                   key-codes/ESC
                   (close-modal-fn ev)
                   37 ;; arrow left
                   (do
                     (when (:prev-page @state)
                       (swap! state update :current-page dec)
                       (get-images-fn)))
                   39 ;; arrow right
                   (do
                     (when (:next-page @state)
                       (swap! state update :current-page inc)
                       (get-images-fn)))
                   true))
        classes (:classes options)]
    (r/create-class
     {:display-name "select panel"
      :component-will-mount
      (fn [_]
        (get-images-fn)
        (events/listen js/window
                       event-type/KEYDOWN
                       esc-fn))
      :component-will-unmount
      (fn [_]
        (events/unlisten js/window
                         event-type/KEYDOWN
                         esc-fn))
      :reagent-render
      (fn [panel-state f]
        [:div.modal-panel
         {:class (get-in options [:classes :modal-panel])}

         [:a.select-close
          {:href "#"
           :on-click close-modal-fn}
          "X"]
         (if @(:value f)
           [:a.select-image-current
            {:class (get-in options [:classes :select-image-current])
             :href "#"
             :on-click close-modal-fn}
            [:span "Current Image:"]
            [:img.formic-image-current
             {:class (get-in options [:classes :image-current])
              :src ((or
                     (:image->src options)
                     (:image->thumbnail options)
                     identity)
                    @(:value f))}]]
           [:h4 "Not Selected"])
         [:div.modal-panel-inner
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

(defn panel-select [panel-state]
  [:div
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
           (name new-state)]])))]])

(defn image-field [{:keys [id err options] :as f}]
  (let [panel-state (r/atom :closed)
        {:keys [image->src
                image->thumbnail]} options]
    (fn [f]
      [inputs/common-wrapper f
       [:div.formic-image-field
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
                [upload-panel panel-state f]))]])]])))

(field/register-component
 :formic-imagemodal
 {:component image-field})
