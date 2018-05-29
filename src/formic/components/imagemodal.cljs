(ns formic.components.imagemodal
  (:require [ajax.core :refer [GET POST]]
            [cljsjs.dropzone]
            [reagent.core :as r]))

;; Server Upload
;; -------------------------------------------------------------------------------

(defn upload-panel [panel-state {:keys [endpoints current-value err]}]
  (r/create-class
   {:component-did-mount
    (fn [this]
      (let [dropzone-options
            {:url (:upload endpoints)
             :headers {"X-CSRF-Token"
                       (.-value (.getElementById
                                 js/document
                                 "__anti-forgery-token"))}}
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
;;            current-value (r/cursor f [:current-value])
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

(defn select-panel [panel-state {:keys [endpoints current-value err]}]
  (let [state (r/atom {:current-page 0
                       :current-images nil
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
                           :params {:page (:current-page @state)}
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
         (case (:mode @state)
           :loaded
           [:div
            [:h5 "Page " (-> @state :current-page inc)]
            (when (or (:next-page @state) (:prev-page @state))
              [:ul
               [:li [:button {:disabled (not (:prev-page @state))
                              :on-click (fn [ev]
                                          (.preventDefault ev)
                                          (swap! state update-in [:current-page] dec)
                                          (get-images))} "prev"]]
               [:li [:button {:disabled (not (:next-page @state))
                              :on-click (fn [ev]
                                          (.preventDefault ev)
                                          (swap! state update-in [:current-page] inc)
                                          (get-images))} "next"]]])
            [:ul.formic-modal-image-grid
             (doall
              (for [i (:current-images @state)
                    :let [thumb-src ((or (:image->thumbnail f) identity) i)
                          current-src ((or (:image->thumbnail f) identity) @current-value)]]
                ^{:key i}
                [:li {:class (when (= thumb-src current-src) "selected")}
                 [:a
                  {:on-click (fn [ev]
                               (.preventDefault ev)
                               (reset! current-value i)
                               (reset! panel-state :closed))}
                  [:img {:src thumb-src}]]]))]]
           :error
           [:div.error
            [:h4 "Loading Error."]
            [:button {:on-click (fn [ev]
                                  (.preventDefault ev)
                                  (get-images))}
             "Retry"]]
           :loading
           [:h4 "Loading"])])})))

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

(defn image-field [f]
  (let [panel-state (r/atom :closed)]
    (fn [f]
      [:div.formic-image-field
       (if @(:current-value f)
         [:img.formic-image-current
          {:src ((or
                  (:image->src f)
                  (:image->thumbnail f)
                  identity)
                 @(:current-value f))}]
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
             (if (get-in f [:endpoints :get-signed])
               [:span "TODO"]
               ;;[s3-upload-panel panel-state f]
               [upload-panel panel-state f]))]])])))
