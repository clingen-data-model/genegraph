(def mini-nav-header
  [:div#header_nav_micro
   [:div.container.container-trans
    [:div.clearfix.row
     [:nav#nav_micro.hidden-sm.hidden-xs
      [:ul.list-inline
       [:li [:a {:href "https://www.clinicalgenome.org/contact/"
                 :title "Contact"}
             "Contact"]]
       [:li [:a {:href "https://www.clinicalgenome.org/search/"
                 :title "Site Search"}
             "Site Search"]]
       [:li [:a {:href "https://www.clinicalgenome.org/events-news/"
                 :title "News and Announcements"}
             "Events and Publications"]]]]]
    [:ol.pull-left.breadcrumb
     [:li [:a {:href "https://www.clinicalgenome.org"}
           "ClinGen"]]
     [:li [:a {:href "https://search.clinicalgenome.org/kb"}
           "Knowledge Base"]]]]])

(def nav-header
  [:div#header_nav_main
   [:div.container.navbar.margin-bottom.none
    [:div.header.clearfix.row
     [:div#header_logo_div.text-muted.pull-left
      [:a#header_logo_href {:href "https://www.clinicalgenome.org/"}
       [:img {:src "images/logo.png"}]]]
     [:button.visible-xs.pull-right.navbar-toggle.btn.btn-default
      {:type "button", :data-toggle "collapse",
       :data-target ".clingen-navbar-collapse"}
      "Navigation"
      [:span.caret]]
     [:form 
      [:div#header_search.input-group
       [:input {:type "text"}]
       [:span.input-group-button
        [:button.btn.btn-default {:type "submit"}]]]]]]])

(def header-highlight
  [:div#header_highlight.container-fluid.container-trans])

(def search-bar
  [:div.container.search-content-header
   [:div.row
    [:div.col-sm-12
     [:div.pull-right.search-content-header-buttons
      [:div.btn-group {:role "group", :aria-label "..."}
       [:div.btn-group {:role "group"}
        [:a.btn.btn-default.dropdown-toggle {:href "#"}
         "ClinGen Curated"
         [:span.caret]]
        [:ul.dropdown-menu
         [:li [:a {:href "#"} "All ClinGen Curated Genes"]]
         [:li.divider {:role "separator"}]
         [:li [:a {:href "#"} "Gene-Disease Validity Curations"]]
         [:li [:a {:href "#"} "Dosage Sensitivity Curations"]]
         [:li [:a {:href "#"} "Clinical Actionability Curations"]]
         [:li [:a {:href "#"} "Gene-Disease Validity Curations"]]
         [:li.divider {:role "separator"}]
         [:li [:a {:href "#"} "Curated Conditions"]]
         [:li [:a {:href "#"} "Curated Genes"]]]]
       [:a.btn.btn-default {:href "#"}
        [:i.glyphicon.glyphicon-edit]
        [:span [:span.hidden-xs] "Share Information"]]]]
     [:h1 [:a.text-black {:href "#"} "Knowledge Base"]]]]])
