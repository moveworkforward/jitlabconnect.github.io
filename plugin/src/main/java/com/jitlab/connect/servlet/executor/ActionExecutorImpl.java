package com.jitlab.connect.servlet.executor;

import com.atlassian.jira.bc.issue.comment.CommentService;
import com.atlassian.jira.bc.issue.link.RemoteIssueLinkService;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.issue.link.RemoteIssueLink;
import com.atlassian.jira.issue.link.RemoteIssueLinkBuilder;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.sal.api.ApplicationProperties;
import com.atlassian.sal.api.message.I18nResolver;
import com.atlassian.streams.api.Html;
import com.atlassian.streams.api.common.Either;
import com.atlassian.streams.api.common.Option;
import com.atlassian.streams.thirdparty.api.*;
import com.jitlab.connect.servlet.entity.actions.Action;
import com.jitlab.connect.servlet.entity.actions.ActivityAction;
import com.jitlab.connect.servlet.entity.actions.CommentAction;
import com.jitlab.connect.servlet.entity.actions.LinkAction;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;

public class ActionExecutorImpl implements ActionExecutor {
    private static final Logger log = LoggerFactory.getLogger(ActionExecutorImpl.class);
    //private IssueLinkManager linkManager;
    private RemoteIssueLinkService linkService;
    private CommentService commentService;
    private ApplicationProperties applicationProperties;
    private ActivityService activityService;
    private I18nResolver i18n;

    public ActionExecutorImpl(I18nResolver i18n, CommentService commentService, ActivityService activityService, RemoteIssueLinkService linkService, ApplicationProperties applicationProperties) {
        this.commentService = commentService;
        this.applicationProperties = applicationProperties;
        this.activityService = activityService;
        //this.linkManager = linkManager;
        this.linkService = linkService;
        this.i18n = i18n;
    }

    public void doComment(CommentAction action, ApplicationUser user, List<MutableIssue> issues) {
        for (MutableIssue issue : issues) {
            log.debug("Push to JIRA comments ({}, {})", issue.getKey(), user.getUsername());

            commentService.create(
                    user,
                    commentService.validateCommentCreate(user,
                            CommentService.CommentParameters
                                    .builder()
                                    .issue(issue)
                                    .author(user)
                                    .body(action.getAction() + " " + action.getUrl().linkJira()
                                            + "{panel}" + action.getText() + "{panel}")
                                    .build()
                    ),
                    true);
        }
    }

    public void doActivity(ActivityAction action, ApplicationUser user, List<MutableIssue> issues) {

        JiraAuthenticationContext authContext = ComponentAccessor.getJiraAuthenticationContext();
        authContext.setLoggedInUser(ComponentAccessor.getUserManager().getUserByKey(user.getUsername()));
        for (MutableIssue issue : issues) {
            log.debug("Push to JIRA activity ({}, {})", issue.getKey(), user.getUsername());

            String title = new StringBuilder("<strong>")
                    .append(user.getDisplayName())
                    .append("</strong> ")
                    .append(" ")
                    .append(action.getTitle())
                    .append(" ")
                    .append(i18n.getText("jitlab-connect.text.for"))
                    .append(" ")
                    .append(issue.getKey())
                    .toString();

            Activity.Builder builder = new Activity.Builder(
                    Application.application("JitLab Connect", URI.create("https://jitlabconnect.github.io/")),
                    new DateTime(),
                    new com.atlassian.streams.api.UserProfile.Builder(user.getUsername()).build());

            Either<ValidationErrors, Activity> result = builder
                    //     .id(Option.option(url)) TODO
                    .target(new ActivityObject
                            .Builder()
                            .urlString(Option.option(issue.getKey()))
                            .build())
                    .title(Option.option(new Html(title)))
                    .content(Option.option(new Html(
                            "<blockquote><p>"
                                    + action.getAction() + " " + action.getUrl().link()
                                    + "</p><div class=\"panel\" style=\"border-width: 1.0px;\"><div class=\"panelContent\"><p>"
                                    + action.getText()
                                    + "</p></div></div></blockquote>")))
                    .url(Option.option(URI.create(action.getUrl().getUrl())))
                    .icon(Option.option(Image.withUrl(URI.create(applicationProperties.getBaseUrl() + "/download/resources/com.jitlab.plugin:jitlab-connect-resources/images/pluginIcon.png"))))
                    .build();
            for (Activity activity : result.right()) {
                log.debug("Push to JIRA activity ({}, {})", issue.getKey(), user.getUsername());
                activityService.postActivity(activity);
            }

            for (ValidationErrors errors : result.left()) {
                log.error("Failed to push JIRA activity ({}, {})", issue.getKey(), errors.toString());
            }
        }
    }

    public void doLink(LinkAction action, ApplicationUser user, List<MutableIssue> issues) {
        for (MutableIssue issue : issues) {
            log.debug("Push to JIRA links ({}, {})", issue.getKey(), user.getUsername());

            boolean isExist = false;
            for (RemoteIssueLink link : linkService.getRemoteIssueLinksForIssue(user, issue).getRemoteIssueLinks()) {
                if (link.getTitle().equals(action.getText())) {
                    isExist = true;
                    break;
                }
            }
            if (isExist) return;

            // add link
            RemoteIssueLinkBuilder linkBuilder = new RemoteIssueLinkBuilder();
            linkBuilder.issueId(issue.getId());
            linkBuilder.applicationName("JitLab Connect");
            linkBuilder.applicationType("com.jitlab.connect");
            linkBuilder.iconUrl(applicationProperties.getBaseUrl() + "/download/resources/com.jitlab.plugin:jitlab-connect-resources/images/pluginIcon.png");
            linkBuilder.relationship("GitLab");
            linkBuilder.title(action.getText());
            linkBuilder.url(action.getUrl().getUrl());
            linkBuilder.build();
            RemoteIssueLink link = linkBuilder.build();
            RemoteIssueLinkService.CreateValidationResult createValidateResult = linkService.validateCreate(user, link); //.validateCreate(authContext.getLoggedInUser(), link);

            if (createValidateResult.isValid()) {
                log.debug("Create a link for JIRA task ({}, {})", issue.getKey(), user.getUsername());
                linkService.create(user, createValidateResult);
            } else {
                log.error("Failed to link JIRA task ({}, {})", issue.getKey(), user.getUsername());
            }

        }
    }

    public void execute(Action action, ApplicationUser user, List<MutableIssue> issues) {
        if (action instanceof CommentAction) {
            log.debug("Start 'Comment' action");
            doComment((CommentAction) action, user, issues);
        } else if (action instanceof ActivityAction) {
            log.debug("Start 'Activity' action");
            doActivity((ActivityAction) action, user, issues);
        } else if (action instanceof LinkAction) {
            log.debug("Start 'Link' action");
            doLink((LinkAction) action, user, issues);
        }
    }
}