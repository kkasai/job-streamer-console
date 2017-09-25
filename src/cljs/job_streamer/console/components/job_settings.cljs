(ns job-streamer.console.components.job-settings
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan]]
            [bouncer.core :as b]
            [bouncer.validators :as v]
            [job-streamer.console.api :as api]))

(def app-name "default")

(defn delete-job [job jobs-channel owner]
  (api/request (str "/" app-name "/job/" (:job/name job)) :DELETE
               {:handler (fn [response]
                           (put! jobs-channel [:delete-job job])
                           (set! (.-href js/location) "#/"))
                :error-handler (fn [response]
                                 (om/set-state! owner :message {:class "error"
                                                                :header "Failed to delete."
                                                                :body [:p (:message response)]}))
                :forbidden-handler (fn [response]
                                     (om/set-state! owner :message {:class "error"
                                                                    :header "Failed to delete."
                                                                    :body [:p "You are unauthorized to delete job."]}))}))

(defn save-settings [job-name method owner category obj & {:keys [handler error-handler forbidden-handler]}]
  (om/set-state! owner [:save-status category] false)
  (api/request (str "/" app-name "/job/" job-name "/settings/" (name category)) method obj
               {:handler (fn [response]
                           (om/set-state! owner [:save-status category] true)
                           (when handler
                             (handler response)))
                :error-handler (fn [response]
                                 (om/set-state! owner [:save-status category] false)
                                 (when error-handler
                                   (error-handler response)))
                :forbidden-handler (fn [response]
                                     (om/set-state! owner [:save-status category] false)
                                     (when forbidden-handler
                                       (forbidden-handler response)))}))

(defcomponent job-settings-view [job owner {:keys [jobs-channel]}]
  (init-state [_]
    {:save-status {:status-notification false
                   :time-monitor false
                   :exclusive false}
     :time-monitor {:time-monitor/duration 0
                    :time-monitor/action ""
                    :time-monitor/notification-type ""}
     :status-notification {:status-notification/type ""
                           :status-notification/batch-status ""
                           :status-notification/exit-status ""
                           :status-notification/status-type :batch-status}
     :status-notification-type :batch-status})
  (will-mount [_]
    (api/request (str "/" app-name "/job/" (:job/name job) "/settings")
                 {:handler (fn [response]
                             (om/set-state! owner :settings response))}))
  (render-state [_ {:keys [settings save-status time-monitor status-notification message roles]}]
    (html
     [:div
      [:div.row {:style {:display (if message "block" "none")}}
       [:div.column
        [:div.ui.message {:class (:class message)}
         [:div.header (:header message)]
         [:div (:body message)]]]]
      [:div.ui.segment
       [:div.ui.top.attached.label "Notification"]
       [:div.content
        (if (some #(= :admin %) roles)
          [:div.ui.input.block.form
           [:div.inline.fields
            [:label "When the"]
            [:div.field
             [:select.ui.selection.dropdown
              {:on-change (fn [e]
                              (om/set-state! owner [:status-notification :status-notification/status-type]
                                             (keyword (.. e -target -value))))}
              [:option {:value "batch-status"} "batch status"]
              [:option {:value "exit-status"} "exit status"]]]
            [:label " is "]
            [:div.field
             (case (:status-notification/status-type status-notification)
                   :exit-status
                   [:input {:type "text"
                            :value (:status-notification/exit-status status-notification)
                            :on-change (fn [e]
                                           (om/set-state! owner [:status-notification :status-notification/exit-status]
                                                          (.. e -target -value)))}]

                   :batch-status
                   [:select.ui.selection.dropdown
                    {:value (:status-notification/batch-status status-notification)
                     :on-change (fn [e]
                                    (om/set-state! owner [:status-notification :status-notification/batch-status]
                                                   (.. e -target -value)))}
                    [:option {:value ""} ""]
                    [:option {:value "abandoned"} "abandoned"]
                    [:option {:value "completed"} "completed"]
                    [:option {:value "failed"} "failed"]])]
            [:label ", send notification by"]
            [:div.field
             [:input {:type "text"
                      :value (:status-notification/type status-notification)
                      :on-change (fn [e]
                                     (om/set-state! owner [:status-notification :status-notification/type]
                                                    (.. e -target -value)))}]]
            [:button.ui.positive.button
             (merge
               {:type "button"
                :on-click (fn [e]
                              (let [status-notification (om/get-state owner :status-notification)
                                    status-notification (case (:status-notification/status-type status-notification)
                                                              :batch-status {:status-notification/type (:status-notification/type status-notification)
                                                                             :status-notification/batch-status
                                                                                                       (keyword "batch-status"
                                                                                                                (:status-notification/batch-status status-notification))}
                                                              :exit-status  (select-keys status-notification [:status-notification/type
                                                                                                              :status-notification/exit-status]))]
                                   (save-settings (:job/name job)
                                                  :PUT
                                                  owner
                                                  :status-notification
                                                  status-notification
                                                  :handler (fn [resp]
                                                               (om/update-state!
                                                                 owner [:settings :job/status-notifications]
                                                                 (fn [notifications]
                                                                     (conj notifications (assoc status-notification
                                                                                                :db/id (:db/id resp))))))
                                                  :error-handler (fn [resp]
                                                                     (om/set-state! owner :message {:class "error"
                                                                                                    :header "Failed to save."
                                                                                                    :body [:p (:message resp)]}))
                                                  :forbidden-handler (fn [_]
                                                                         (om/set-state! owner :message {:class "error"
                                                                                                        :header "Failed to save."
                                                                                                        :body [:p "You are unauthorized to chnange job setting."]})))))}
               (when-not (b/valid? status-notification
                                   :status-notification/exit-status [[v/required :pre #(= (:status-notification/status-type %) :exit-status)]]
                                   :status-notification/type [[v/required]])
                         {:class "disabled"}) )
             "Add"]]])
        (if (not-empty (:job/status-notifications settings))
          [:table.ui.compact.table
           [:thead
            [:tr
             [:th "Status"]
             [:th "Notification"]
             (if (some #(= :admin %) roles)
               [:th ""])]]
           [:tbody
            (for [notification (:job/status-notifications settings)]
              (let [status-type (cond
                                  (:status-notification/batch-status notification) :batch-status
                                  (:status-notification/exit-status  notification) :exit-status)]
                [:tr
                 (case status-type
                   :batch-status
                   [:td
                    [:div.ui.orange.label "Batch status"]
                    (name (get-in notification [:status-notification/batch-status] ""))]

                   :exit-status
                   [:td
                    [:div.ui.olive.label "Exit status"]
                    (:status-notification/exit-status notification)]
                   [:td
                    [:div.ui.label "Unknown"]])
                 [:td (:status-notification/type notification)]
                 (if (some #(= :admin %) roles)
                   [:td [:a
                         [:i.remove.red.icon
                          {:on-click (fn [_]
                                         (save-settings (:job/name job)
                                                        :PUT
                                                        owner
                                                        :status-notification
                                                        {:db/id (:db/id notification)}
                                                        :handler (fn [_]
                                                                     (om/update-state! owner [:settings :job/status-notifications]
                                                                                       (fn [st] (remove #(= (:db/id %) (:db/id notification)) st))))
                                                        :error-handler (fn [resp]
                                                                           (om/set-state! owner :message {:class "error"
                                                                                                          :header "Failed to save."
                                                                                                          :body [:p (:message resp)]}))
                                                        :forbidden-handler (fn [_]
                                                                               (om/set-state! owner :message {:class "error"
                                                                                                              :header "Failed to save."
                                                                                                              :body [:p "You are unauthorized to chnange job setting."]}))))}]]])]))]])]]
      [:div.ui.segment
       [:div.ui.top.attached.label "Schedule settings"]
       [:div.content
        [:h4.ui.header "Exclusive execution"]
        [:div.ui.toggle.checkbox
         (merge
          {:on-click (fn [e]
                       (let [cb (.getElementById js/document "exclusive-checkbox")
                             checked (.-checked cb)]
                         (save-settings (:job/name job) (if checked :PUT :DELETE)
                                        owner :exclusive
                                        {:job/exclusive? checked}
                                        :handler (fn [_] (om/set-state! owner [:settings :job/exclusive?] checked))
                                        :error-handler (fn [resp]
                                                         (om/set-state! owner :message {:class "error"
                                                                                        :header "Failed to save."
                                                                                        :body [:p (:message resp)]}))
                                        :forbidden-handler (fn [_]
                                                             (om/set-state! owner :message {:class "error"
                                                                                  :header "Failed to save."
                                                                                  :body [:p "You are unauthorized to chnange job setting."]})))))}
          (when (:job/exclusive? settings)
            {:class "checked"}))
         [:input {:id "exclusive-checkbox"
                  :type "checkbox"
                  :checked (:job/exclusive? settings)
                  :disabled (some? (some #(or (= :watcher %) (= :operator %)) roles))}]
         [:label {:for "exclusive-checkbox"} "If this job should be executed exclusively, check this"
          (when (:exclusive save-status) [:i.checkmark.green.icon])]]

        [:h4.ui.header "Execution constraints"]
        (if-let [settings-time-monitor (not-empty (:job/time-monitor settings))]
          [:div
           "When it's passed for "
           (:time-monitor/duration settings-time-monitor) "minutes,"
           (case (:time-monitor/action settings-time-monitor)
             :action/alert (str "send an alert by \"" (:time-monitor/notification-type settings-time-monitor) "\"")
             :action/stop (str "stop the job."))

           [:a {:on-click (fn [_]
                            (save-settings (:job/name job) :DELETE
                                           owner :time-monitor {}
                                           :handler (fn [_]
                                                      (om/set-state! owner [:settings :job/time-monitor] nil))
                                           :error-handler (fn [resp]
                                                            (om/set-state! owner :message {:class "error"
                                                                                           :header "Failed to save."
                                                                                           :body [:p (:message resp)]}))
                                           :forbidden-handler (fn [_]
                                                                (om/set-state! owner :message {:class "error"
                                                                                               :header "Failed to save."
                                                                                               :body [:p "You are unauthorized to chnange job setting."]}))))}
            (if (some #(= :admin %) roles)
              [:i.remove.red.icon])]]

          (if (some #(= :admin %) roles)
            [:div.ui.right.labeled.block.input.form
             [:div.inline.fields
              [:div.field
               [:input {:id "time-monitor-duration"
                        :type "number"
                        :value (:time-monitor/duration time-monitor)
                        :on-change (fn [_]
                                       (let [duration (js/parseInt (.-value (.getElementById js/document "time-monitor-duration")))]
                                            (om/set-state! owner [:time-monitor :time-monitor/duration] (if (> duration 0) duration 0))))}]]
              [:div.ui.label "minutes"]
              [:label "Action:"]
              [:div.field
               [:select.ui.selection.dropdown
                {:id "time-monitor-action"
                 :value (if-let [action (:time-monitor/action time-monitor)]
                                (name action) "")
                 :on-change (fn [_]
                                (let [action (.-value (.getElementById js/document "time-monitor-action"))]
                                     (om/set-state! owner [:time-monitor :time-monitor/action]
                                                    (keyword "action" action))))}
                [:option {:value ""} ""]
                [:option {:value "alert"} "Alert"]
                [:option {:value "stop"} "Stop"]]
               (when (= (:time-monitor/action time-monitor) :action/alert)
                     [:input {:type "text" :id "time-monitor-notification-type"
                              :value (:time-monitor/notification-type time-monitor)
                              :placeholder "Notification"
                              :on-change (fn [_]
                                             (let [notification-type (.-value (.getElementById js/document "time-monitor-notification-type"))]
                                                  (om/set-state! owner [:time-monitor :time-monitor/notification-type] notification-type)))}])]
              [:button.ui.tiny.positive.button
               (merge {:type "button"
                       :on-click (fn [_]
                                     (save-settings (:job/name job) :PUT
                                                    owner :time-monitor
                                                    time-monitor
                                                    :handler (fn [_]
                                                                 (om/set-state! owner [:settings :job/time-monitor] time-monitor))
                                                    :error-handler (fn [resp]
                                                                       (om/set-state! owner :message {:class "error"
                                                                                                      :header "Failed to save."
                                                                                                      :body [:p (:message resp)]}))
                                                    :forbidden-handler (fn [_]
                                                                           (om/set-state! owner :message {:class "error"
                                                                                                          :header "Failed to save."
                                                                                                          :body [:p "You are unauthorized save job."]}))))}
                      (when (or (= (:time-monitor/duration time-monitor) 0)
                                (not (keyword? (:time-monitor/action time-monitor)))
                                (and (= (:time-monitor/action time-monitor) :action/alert)
                                     (empty? (:time-monitor/notification-type time-monitor))))
                            {:class "disabled"}))
               "Save"]]]))]]

      (if (some #(= :admin %) roles)
        [:div.ui.segment
         [:div.ui.top.attached.label "Danger Zone"]
         [:div.content
          [:h4.ui.header "Delete this job"]
          [:div.ui.two.column.grid
           [:div.column "Once you delete a job, there is no going back."]
           [:div.right.aligned.column
            [:button.ui.red.button
             {:type "button"
              :on-click (fn [e]
                            (.preventDefault e)
                            (put! jobs-channel [:open-dangerously-dialog
                                                {:ok-handler (fn []
                                                                 (delete-job job jobs-channel owner))
                                                 :answer (:job/name job)}]))}
             "Delete this job"]]]]])])))
