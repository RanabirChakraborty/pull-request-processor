package org.jboss.pull.processor.impl.action;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.stream.Collectors;

import org.jboss.pull.processor.Action;
import org.jboss.pull.processor.ActionContext;
import org.jboss.pull.processor.Main;
import org.jboss.pull.processor.data.Attributes;
import org.jboss.pull.processor.data.EvaluatorData;
import org.jboss.pull.processor.data.IssueData;
import org.jboss.pull.processor.data.LabelData;
import org.jboss.pull.processor.data.PullRequestData;
import org.jboss.set.aphrodite.Aphrodite;
import org.jboss.set.aphrodite.domain.Label;
import org.jboss.set.aphrodite.domain.PullRequest;
import org.jboss.set.aphrodite.domain.Repository;

public class SetLabelsAction implements Action {

    private Map<Repository, List<Label>> dataLabels;

    public SetLabelsAction() {
       dataLabels = new HashMap<>();
    }

    @Override
    public void execute(ActionContext actionContext, List<EvaluatorData> processorDataList) {

       Aphrodite aphrodite = actionContext.getAphrodite();

       ExecutorService service = Executors.newFixedThreadPool(10);
       try {
           service.invokeAll(processorDataList.stream().map(e -> new EvaluatorProcessingTask(aphrodite, actionContext,  e)).collect(Collectors.toList()));
       } catch (InterruptedException ex) {
           Main.logger.log(Level.WARNING, " interrupted ", ex);
       } finally {
           service.shutdown();
       }
    }

    private class EvaluatorProcessingTask implements Callable<Void> {

        private EvaluatorData root;

        private Aphrodite aphrodite;

        private ActionContext actionContext;

        public EvaluatorProcessingTask(Aphrodite aphrodite, ActionContext actionContext, EvaluatorData root) {
            this.root = root;
            this.aphrodite = aphrodite;
            this.actionContext = actionContext;
        }

        @Override
        public Void call() throws Exception {
            PullRequestData link = root.getAttributeValue(Attributes.PULL_REQUEST);
            URL pullRequestURL = link.getLink();

            PullRequest pullRequest = null;
            try {
                pullRequest = aphrodite.getPullRequest(pullRequestURL);
                List<IssueData> issues = root.getAttributeValue(Attributes.ISSUES_RELATED);
                Map<String, List<LabelData>> labels = root.getAttributeValue(Attributes.LABELS);

                // rearrange labels. mapping from issue - labels to labels.name -> labels
                Map<String, List<LabelData>> labelsRearrange = new HashMap<>();
                for(IssueData issue : issues) {
                    List<LabelData> labelsData = labels.get(issue.getLabel());
                    for(LabelData labelData : labelsData) {
                        List<LabelData> currentData = labelsRearrange.getOrDefault(labelData.getName(),  new ArrayList<LabelData>());
                        labelsRearrange.putIfAbsent(labelData.getName(), currentData);
                        currentData.add(labelData);
                    }
                }

                // consider that the flag is up if all the issues in the same PR are up.
                List<String> added = new ArrayList<>();
                List<String> removed = new ArrayList<>();
                for(Map.Entry<String, List<LabelData>> e : labelsRearrange.entrySet()) {
                    if(e.getValue().stream().filter(j -> !j.isOk()).findAny().isPresent()) {
                        removed.add(e.getKey());
                    } else {
                        added.add(e.getKey());
                    }
                }
                String addedString = added.stream().collect(Collectors.joining(","));
                String removedString = removed.stream().collect(Collectors.joining(","));
                Main.logger.info(pullRequest.getURL() + " labels SET [" + addedString + "] UNSET [" + removedString + "]");

                if(!actionContext.getWrite()) {
                    Main.logger.log(Level.WARNING, " running in dry run mode (SKIP set labels) " + pullRequest);
                    return null;
                }
                if(!root.getAttributeValue(Attributes.WRITE_PERMISSION)) {
                    Main.logger.log(Level.WARNING, " I don't have writing permission for " + pullRequest);
                    return null;
                }

                List<String> streams = new ArrayList<>();
                streams.addAll(root.getAttributeValue(Attributes.STREAMS));
                streams.retainAll(actionContext.getStreams());
                if(streams.isEmpty()) {
                    Main.logger.log(Level.WARNING, " The patch does not belong to any stream being processed (SKIP set labels) " + pullRequest);
                    return null;
                }
                List<Label> currentLabels = aphrodite.getLabelsFromPullRequest(pullRequest);
                List<String> currentLabelsStr = currentLabels.stream().map(e -> e.getName()).collect(Collectors.toList());

                List<String> newLabelsStrSet = new ArrayList<>();
                newLabelsStrSet.addAll(currentLabelsStr);
                newLabelsStrSet.removeAll(removed);
                newLabelsStrSet.removeAll(actionContext.getAllowedStreams());
                newLabelsStrSet.addAll(root.getAttributeValue(Attributes.STREAMS));
                added.removeAll(newLabelsStrSet);
                newLabelsStrSet.addAll(added);

                // if they are the same set of labels, skip the update
                List<Label> newLabelsSet = toLabels(pullRequest, newLabelsStrSet);
                if(newLabelsStrSet.size() == currentLabelsStr.size() && currentLabelsStr.removeAll(newLabelsStrSet) && currentLabelsStr.isEmpty()) {
                     return null;
                }

                Main.logger.info(pullRequest.getURL() + " executing labels SET " + (newLabelsStrSet));
                aphrodite.setLabelsToPullRequest(pullRequest, newLabelsSet);
            } catch(Exception e) {
                Main.logger.log(Level.WARNING, "not found something " + pullRequest, e);
            }
            return null;
        }

        private List<Label> toLabels(PullRequest pullRequest, List<String> labelsStr) throws Exception {
              final List<Label> tmp;
              synchronized (dataLabels) {
                  if(!dataLabels.containsKey(pullRequest.getRepository())) {
                      dataLabels.put(pullRequest.getRepository(), aphrodite.getLabelsFromRepository(pullRequest.getRepository()));
                  }
                  tmp = dataLabels.get(pullRequest.getRepository());
              }
              List<Label> labelsForPatch = labelsStr.stream()
                       .filter(e-> stringToLabel(e, tmp).isPresent())
                       .map(e -> stringToLabel(e, tmp).get()).collect(Collectors.toList());

              return labelsForPatch;
        }

        private Optional<Label> stringToLabel(String label, List<Label> availables) {
            return availables.stream().filter(e -> e.getName().equals(label)).findFirst();
        }
    }
}
