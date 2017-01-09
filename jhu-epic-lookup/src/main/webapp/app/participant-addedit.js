angular.module('os.plugins.jhu-epic-lookup')
  .controller('jhuEpicParticipantAddEditCtrl',
    function($scope, $q, $interval, $document, Participant, Alerts) {
      $scope.partCtx.includeSiteTypes = ['EPIC'];

      var participant = $scope.cpr.participant;
      var matchingFn = participant.getMatchingParticipants;

      participant.getMatchingParticipants = function() {
        return matchingFn.apply(participant, [{returnThis: true}]).then(
          function(matches) {
            var hasEmpiOrMrn = !!participant.empi || hasMrn(participant);
            if (hasEmpiOrMrn && !matches || matches.length == 0) {
              //
              // Prohibit users from registering participants with
              // non-existing eMPI/MRN in EPIC
              //
              Alerts.error('participant.no_matching_epic_participant');
              return $q.reject();
            }

            return matches.filter(function(match) {return !participant.id || participant.id != match.participant.id});
         });
      }

      var hideFn = $interval(
        function() {
          //
          // UID field is not used
          //
          var uidInputEl = $document.find('input[name="uid"]');
          uidInputEl.parent().parent().hide();

          if (!$scope.partCtx.twoStepReg) {
            //
            // Hide MPI field in regular workflow for participants
            // not sourced from EPIC
            //
            if ($scope.cpr.participant.source != 'EPIC' && !$scope.cpr.participant.id) {
              var empiInputEl = $document.find('input[name="empi"]');
              empiInputEl.parent().parent().hide();
            }
          }

          //
          // No MRNs for non-EPIC participants
          //
          if ($scope.cpr.participant.source != 'EPIC' && !$scope.cpr.participant.id) {
            var pmiInputEl = $document.find('div[id="pmiForm"]');
            pmiInputEl.hide();
          }

          if (uidInputEl.length > 0) {
            //
            // Cancel only when we are sure all required fields are hidden
            //
            $interval.cancel(hideFn);
          }
        }, 100, 0, false);

      function hasMrn(participant) {
        return participant.pmis && participant.pmis.filter(
          function(pmi) {
            return !!pmi.mrn;
          }
        ).length > 0;
      }
    }
  );
