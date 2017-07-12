// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner.tasks;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.yugabyte.yw.commissioner.TaskList;
import com.yugabyte.yw.commissioner.TaskListQueue;
import com.yugabyte.yw.commissioner.UserTaskDetails.SubTaskGroupType;
import com.yugabyte.yw.commissioner.tasks.subtasks.RemoveUniverseEntry;
import com.yugabyte.yw.forms.UniverseTaskParams;
import com.yugabyte.yw.models.Universe;

public class DestroyUniverse extends UniverseTaskBase {
  public static final Logger LOG = LoggerFactory.getLogger(DestroyUniverse.class);

  @Override
  public void run() {
    try {
      // Create the task list sequence.
      taskListQueue = new TaskListQueue(userTaskUUID);

      // Update the universe DB with the update to be performed and set the 'updateInProgress' flag
      // to prevent other updates from happening.
      Universe universe = lockUniverseForUpdate(-1 /* expectedUniverseVersion */);

      // Create tasks to destroy the existing nodes.
      createDestroyServerTasks(universe.getNodes())
          .setSubTaskGroupType(SubTaskGroupType.RemovingUnusedServers);

      // Create tasks to remove the universe entry from the Universe table.
      createRemoveUniverseEntryTask()
          .setSubTaskGroupType(SubTaskGroupType.RemovingUnusedServers);

      // Update the swamper target file (implicitly calls setSubTaskGroupType)
      createSwamperTargetUpdateTask(true /* removeFile */, SubTaskGroupType.ConfigureUniverse);

      // Run all the tasks.
      taskListQueue.run();
    } catch (Throwable t) {
      // If for any reason destroy fails we would just unlock the universe for update
      try {
        unlockUniverseForUpdate();
      } catch (Throwable t1) {
        // Ignore the error
      }
      LOG.error("Error executing task {} with error='{}'.", getName(), t.getMessage(), t);
      throw t;
    }
    LOG.info("Finished {} task.", getName());
  }

  public TaskList createRemoveUniverseEntryTask() {
    TaskList taskList = new TaskList("RemoveUniverseEntry", executor);
    UniverseTaskParams params = new UniverseTaskParams();
    // Add the universe uuid.
    params.universeUUID = taskParams().universeUUID;
    // Create the Ansible task to destroy the server.
    RemoveUniverseEntry task = new RemoveUniverseEntry();
    task.initialize(params);
    // Add it to the task list.
    taskList.addTask(task);
    taskListQueue.add(taskList);
    return taskList;
  }
}
