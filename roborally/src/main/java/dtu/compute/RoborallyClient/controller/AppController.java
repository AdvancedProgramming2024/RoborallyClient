/*
 *  This file is part of the initial project provided for the
 *  course "Project in Software Development (02362)" held at
 *  DTU Compute at the Technical University of Denmark.
 *
 *  Copyright (C) 2019, 2020: Ekkart Kindler, ekki@dtu.dk
 *
 *  This software is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; version 2 of the License.
 *
 *  This project is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this project; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 */
package dtu.compute.RoborallyClient.controller;

import com.google.gson.*;
import dtu.compute.RoborallyClient.fileaccess.model.SpaceTemplate;
import dtu.compute.RoborallyClient.model.Heading;
import dtu.compute.RoborallyClient.view.SpaceView;
import dtu.compute.RoborallyClient.fields.FieldAction;
import dtu.compute.designpatterns.observer.Observer;
import dtu.compute.designpatterns.observer.Subject;

import dtu.compute.RoborallyClient.fileaccess.Adapter;
import dtu.compute.RoborallyClient.fileaccess.LoadSave;
import dtu.compute.RoborallyClient.fileaccess.model.GameTemplate;
import dtu.compute.RoborallyClient.fileaccess.model.PlayerTemplate;
import dtu.compute.RoborallyClient.model.Command;
import dtu.compute.RoborallyClient.online.RequestCenter;
import dtu.compute.RoborallyClient.RoboRallyClient;

import dtu.compute.RoborallyClient.online.ResourceLocation;
import dtu.compute.RoborallyClient.online.Response;
import dtu.compute.RoborallyClient.view.UpgradeCardFieldView;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.*;

/**
 * ...
 *
 * @author Ekkart Kindler, ekki@dtu.dk
 *
 */
public class AppController implements Observer {

    final private List<Integer> PLAYER_NUMBER_OPTIONS = Arrays.asList(2, 3, 4, 5, 6);

    @Getter
    final private RoboRallyClient client;
    private volatile Thread waitForPlayers;
    private volatile Thread waitForGame;
    private final Gson gson;

    public AppController(@NotNull RoboRallyClient client) {
        this.client = client;

        GsonBuilder simpleBuilder = new GsonBuilder().
                registerTypeAdapter(FieldAction.class, new Adapter<FieldAction>()).
                setPrettyPrinting();
        gson = simpleBuilder.create();
    }

    public void pollServer() {
        System.out.println("Polling server");
        if (client.getLobbyId() == null) {
            return;
        }
        try {
            client.setPoll(true);
            Response<JsonObject> response = RequestCenter.getRequestJson(ResourceLocation.makeUri(ResourceLocation.gameStatePath(client.getLobbyId())+"/"+client.getPlayerName()));
            if (!response.getStatusCode().is2xxSuccessful()) {
                System.out.println("Error: " + response.getStatusCode());
                return;
            }
            JsonObject gameStateJson = response.getItem().getAsJsonObject();
            GsonBuilder simpleBuilder = new GsonBuilder().
                    registerTypeAdapter(FieldAction.class, new Adapter<FieldAction>()).
                    setPrettyPrinting().setLenient();
            Gson gson = simpleBuilder.create();
            GameTemplate gameState = gson.fromJson(gameStateJson.get("gameState").getAsString(), GameTemplate.class);
            if (gameState == null || client.getBoardView() == null) return;
            if (!gameState.timeStamp.equals(client.getLastUpdate())) {
                client.setGameState(gameState);
                Platform.runLater(() -> client.updateBoardView(client.getGameState()));
                client.setLastUpdate(gameState.timeStamp);
            }
            /*if (!(gameState.playPhase == Phase.ACTIVATION.ordinal() || gameState.playPhase == Phase.UPGRADE.ordinal())) suspendPolling();*/

            JsonArray lasers = gameStateJson.get("lasers").getAsJsonArray();
            if (lasers.size() == 0) SpaceView.destroyLasers();
            gameState = client.getGameState();
            for (JsonElement laser : lasers) {
                JsonObject laserObj = laser.getAsJsonObject();
                JsonArray LOS = laserObj.get("LOS").getAsJsonArray();
                int heading = laserObj.get("heading").getAsInt();

                List<SpaceTemplate> spaces = new ArrayList<>();
                for (JsonElement los : LOS) {
                    JsonObject spaceObject = los.getAsJsonObject();
                    int x = spaceObject.get("x").getAsInt();
                    int y = spaceObject.get("y").getAsInt();

                    spaces.add(gameState.board.spaces.get(x * gameState.board.height + y));
                }
                while (client.getBoardView().getSpaces()[gameState.board.height-1][gameState.board.width-1].gameState != gameState) {
                    Thread.sleep(100);
                }
                Platform.runLater(() -> SpaceView.drawLaser(spaces, Heading.values()[heading]));
            }
            if (gameState.winnerName != null) {
                client.suspendPolling();
                Platform.runLater(client::displayWinner);
            }
        } catch (IOException | InterruptedException e) {
            System.out.println("Error in polling server: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }


    public void newLobby() {
        TextInputDialog nameInput = new TextInputDialog();
        nameInput.setTitle("Player name");
        nameInput.setHeaderText("Please state your name");
        Optional<String> name = nameInput.showAndWait();

        try {
            if (name.isEmpty()) {
                return;
            }
            while (name.get().trim().isEmpty()) {
                Alert alert = new Alert(AlertType.ERROR);
                alert.setTitle("Error");
                alert.setHeaderText("You must enter a name");
                alert.showAndWait();

                name = nameInput.showAndWait();
                if (name.isEmpty()) {
                    return;
                }
            }
            Map<String, Object> playerName = Map.of("playerName", name.get());
            Response<String> lobbyResponse = RequestCenter.postRequest(ResourceLocation.makeUri(ResourceLocation.lobbies), playerName);
            if (!lobbyResponse.getStatusCode().is2xxSuccessful()) {
                Alert alert = new Alert(AlertType.ERROR);
                alert.setTitle("Error");
                alert.setHeaderText(lobbyResponse.getItem());
                alert.showAndWait();
                return;
            }
            client.setLobbyId(lobbyResponse.getItem());
            client.setPlayerName(name.get());
            client.createLobbyView();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }

        startWaitingForPlayers();
        startWaitingForGame();
    }

    public void showLobbies() {
        try {
            Response<String> lobbies = RequestCenter.getRequest(ResourceLocation.makeUri(ResourceLocation.lobbies));
            getClient().createJoinView(lobbies);
        } catch (IOException | InterruptedException ex) {
            throw new RuntimeException(ex);
        }
    }

    public void joinLobby(String id) {
        try {
            if (id.isEmpty()) {
                Alert alert = new Alert(AlertType.ERROR);
                alert.setTitle("Error");
                alert.setHeaderText("No lobbyID given");
                alert.showAndWait();
                return;
            }
            Response<String> lobbyFull = RequestCenter.getRequest(ResourceLocation.makeUri(ResourceLocation.joinLobbyPath(id)));
            if (!lobbyFull.getStatusCode().is2xxSuccessful()) {
                Alert alert = new Alert(AlertType.ERROR);
                alert.setTitle("Error");
                alert.setHeaderText(lobbyFull.getItem());
                alert.showAndWait();
                return;
            }
            Response<String> lobbyResponse = RequestCenter.getRequest(ResourceLocation.makeUri(ResourceLocation.lobbyPath(id)));
            if (!lobbyResponse.getStatusCode().is2xxSuccessful() || !lobbyFull.getStatusCode().is2xxSuccessful()) {
                Alert alert = new Alert(AlertType.ERROR);
                alert.setTitle("Error");
                alert.setHeaderText(lobbyResponse.getItem());
                alert.showAndWait();
                return;
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }

        TextInputDialog nameInput = new TextInputDialog();
        nameInput.setTitle("Player name");
        nameInput.setHeaderText("Please state your name");
        Optional<String> name = nameInput.showAndWait();

        try {
            if (name.isEmpty()) {
                return;
            }
            while (name.get().trim().isEmpty()) {
                Alert alert = new Alert(AlertType.ERROR);
                alert.setTitle("Error");
                alert.setHeaderText("You must enter a name");
                alert.showAndWait();

                name = nameInput.showAndWait();
                if (name.isEmpty()) {
                    return;
                }
            }
            Map<String, Object> playerName = Map.of("playerName", name.get());
            Response<String> joinResponse = RequestCenter.postRequest(ResourceLocation.makeUri(ResourceLocation.joinLobbyPath(id)), playerName);
            if (!joinResponse.getStatusCode().is2xxSuccessful()) {
                Alert alert = new Alert(AlertType.ERROR);
                alert.setTitle("Error");
                alert.setHeaderText(joinResponse.getItem());
                alert.showAndWait();
                return;
            }
            client.setLobbyId(id);
            client.setPlayerName(name.get());
            client.createLobbyView();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }

        startWaitingForPlayers();
        startWaitingForGame();
    }

    public void leaveLobby() {
        if (getClient().getLobbyId() == null) return;
        try {
            Map<String, Object> playerName = Map.of("playerName", client.getPlayerName());
            Response<String> response = RequestCenter.postRequest(ResourceLocation.makeUri(ResourceLocation.leaveLobbyPath(client.getLobbyId())), playerName);
            if (!response.getStatusCode().is2xxSuccessful()) {
                Alert alert = new Alert(AlertType.ERROR);
                alert.setTitle("Error");
                alert.setHeaderText(response.getItem());
                alert.showAndWait();
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        stopWaiting();
        client.returnToMenu();
    }

    private void waitForPlayers() {
        Thread thisThread = Thread.currentThread();
        while (waitForPlayers == thisThread) {
            try {
                Response<JsonObject> response = RequestCenter.getRequestJson(ResourceLocation.makeUri(ResourceLocation.lobbyStatePath(client.getLobbyId())));
                client.updateLobbyView(response.getItem());
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        System.out.println("Thread has stopped");
    }

    private void waitForGame() {
        Thread thisThread = Thread.currentThread();
        while (waitForGame == thisThread) {
            try {
                Thread.sleep(2000);
                Response<JsonObject> response = RequestCenter.getRequestJson(ResourceLocation.makeUri(
                        ResourceLocation.gameStatePath(client.getLobbyId())+"/"+ client.getPlayerName()));
                if (!response.getStatusCode().is2xxSuccessful()) {
                    continue;
                }
                GameTemplate gameState = gson.fromJson(response.getItem().getAsJsonObject().get("gameState").getAsString(), GameTemplate.class);

                Platform.runLater(() -> startGame(gameState));
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        System.out.println("Thread has stopped");
    }

    public void startWaitingForPlayers() {
        if (waitForPlayers == null) {
            waitForPlayers = new Thread(this::waitForPlayers);
            waitForPlayers.start();
        }
    }
    public void startWaitingForGame() {
        if (waitForGame == null) {
            waitForGame = new Thread(this::waitForGame);
            waitForGame.start();
        }
    }

    public void stopWaiting() {
        if (waitForPlayers != null) {
            Thread tempThread = waitForPlayers;
            waitForPlayers = null;
            try {
                tempThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (waitForGame != null) {
            Thread tempThread = waitForGame;
            waitForGame = null;
            try {
                tempThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void startGame(GameTemplate gameState) {
        stopWaiting();
        if (client.getBoardView() == null) client.createBoardView(gameState);
    }

    public void createGame() {
        ChoiceDialog<String> mapDialog = new ChoiceDialog<>("dizzy_highway", "dizzy_highway", "high_octane");
        mapDialog.setTitle("Map selection");
        mapDialog.setHeaderText("Select map to play on");
        Optional<String> mapName = mapDialog.showAndWait();
        if (mapName.isEmpty()) {
            return;
        }
        try {
            JsonObject info = new JsonObject();
            info.addProperty("mapName", mapName.get());
            info.addProperty("playerName", client.getPlayerName());

            Response<JsonObject> response = RequestCenter.postRequestJson(ResourceLocation.makeUri(ResourceLocation.gamePath(client.getLobbyId())), info);
            if (!response.getStatusCode().is2xxSuccessful()) {
                Alert alert = new Alert(AlertType.ERROR);
                alert.setTitle("Error");
                alert.setHeaderText(response.getItem().getAsJsonObject().get("info").getAsString());
                alert.showAndWait();
            } else {
                GameTemplate gameState = gson.fromJson(response.getItem().getAsJsonObject().get("gameState").getAsString(), GameTemplate.class);
                startGame(gameState);
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean moveCards(@NotNull String source, @NotNull String target, PlayerTemplate player) {
        GameTemplate gameState;

        int sourceIndex = Integer.parseInt(source.split(",")[1]);
        boolean sourceIsProgrammingCard = source.split(",")[0].equals("P");
        int targetIndex = Integer.parseInt(target.split(",")[1]);
        boolean targetIsProgrammingCard = target.split(",")[0].equals("P");
        if ((sourceIsProgrammingCard ? player.program : player.hand)[sourceIndex] == -1 ||
                (targetIsProgrammingCard ? player.program : player.hand)[targetIndex] != -1) {
            return false;
        }

        JsonObject info = new JsonObject();
        info.addProperty("sourceIndex", sourceIndex);
        info.addProperty("targetIndex", targetIndex);
        info.addProperty("sourceIsProgram", sourceIsProgrammingCard);
        info.addProperty("targetIsProgram", targetIsProgrammingCard);
        info.addProperty("playerName", client.getPlayerName());
        try {
            Response<JsonObject> response = RequestCenter.postRequestJson(ResourceLocation.makeUri(ResourceLocation.playerCardMovementPath(client.getLobbyId(), player.id)), info);
            if (!response.getStatusCode().is2xxSuccessful()) {
                Alert alert = new Alert(AlertType.ERROR);
                alert.setTitle("Error");
                alert.setHeaderText(response.getItem().getAsString());
                alert.showAndWait();
                return false;
            } else {
                gameState = gson.fromJson(response.getItem().getAsJsonObject().get("gameState").getAsString(), GameTemplate.class);
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }

        client.updateBoardView(gameState);
        return true;
    }

    public boolean sendReadySignal() {
        try {
            GameTemplate gameState = client.getGameState();
            int playerId = -1;
            for (int i = 0; i < gameState.players.size(); i++) {
                if (gameState.players.get(i).name.equals(client.getPlayerName())) {
                    playerId = i;
                    break;
                }
            }
            Response<String> response = RequestCenter.getRequest(ResourceLocation.makeUri(ResourceLocation.playerReadyPath(client.getLobbyId(), playerId)));
            if (!response.getStatusCode().is2xxSuccessful()) {
                Alert alert = new Alert(AlertType.ERROR);
                alert.setTitle("Error");
                alert.setHeaderText(response.getItem());
                alert.showAndWait();
                return false;
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        return true;
    }

    public void sendChoice(Command command) {
        GameTemplate gameState = client.getGameState();
        int playerId = -1;
        for (int i = 0; i < gameState.players.size(); i++) {
            if (gameState.players.get(i).name.equals(client.getPlayerName())) {
                playerId = i;
                break;
            }
        }
        try {
            Map<String, Object> args = Map.of("command", command.ordinal());
            Response<String> response = RequestCenter.postRequest(ResourceLocation.makeUri(ResourceLocation.playerChoicePath(client.getLobbyId(), playerId)), args);
            if (!response.getStatusCode().is2xxSuccessful()) {
                Alert alert = new Alert(AlertType.ERROR);
                alert.setTitle("Error");
                alert.setHeaderText(response.getItem());
                alert.showAndWait();
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void buyUpgrade(int shopIndex) {
        JsonObject info = new JsonObject();
        info.addProperty("shopIndex", shopIndex);

        String lobbyId = getClient().getLobbyId();
        int playerId = -1;
        for (PlayerTemplate player : getClient().getGameState().players) {
            if (player.name.equals(getClient().getPlayerName())) {
                playerId = player.id;
                break;
            }
        }
        try {
            Response<JsonObject> response = RequestCenter.postRequestJson(ResourceLocation.makeUri(ResourceLocation.buyUpgradePath(lobbyId, playerId)), info);
            if (!response.getStatusCode().is2xxSuccessful()) {
                System.out.println("Couldn't buy upgrade");
                Alert responseAlert = new Alert(Alert.AlertType.ERROR);
                responseAlert.setTitle("Error");
                responseAlert.setHeaderText(response.getItem().get("info").getAsString());
                responseAlert.showAndWait();
                return;
            }
            System.out.println("Upgrade bought successfully");
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void discardUpgrade(int index, UpgradeCardFieldView.Placement placement) {
        String lobbyId = getClient().getLobbyId();
        int playerId = -1;
        for (PlayerTemplate player : getClient().getGameState().players) {
            if (player.name.equals(getClient().getPlayerName())) {
                playerId = player.id;
                break;
            }
        }
        try {
            JsonObject info = new JsonObject();
            info.addProperty("index", index);
            info.addProperty("isPermanent", placement == UpgradeCardFieldView.Placement.PERMANENT);
            Response<JsonObject> response = RequestCenter.postRequestJson(ResourceLocation.makeUri(ResourceLocation.discardUpgradePath(lobbyId, playerId)), info);
            if (!response.getStatusCode().is2xxSuccessful()) {
                Alert responseAlert = new Alert(Alert.AlertType.ERROR);
                responseAlert.setTitle("Error");
                responseAlert.setHeaderText(response.getItem().get("info").getAsString());
                responseAlert.showAndWait();
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void toggleUpgrade(int index, UpgradeCardFieldView.Placement placement) {
        String lobbyId = getClient().getLobbyId();
        int playerId = -1;
        for (PlayerTemplate player : getClient().getGameState().players) {
            if (player.name.equals(getClient().getPlayerName())) {
                playerId = player.id;
                break;
            }
        }
        try {
            JsonObject info = new JsonObject();
            info.addProperty("index", index);
            info.addProperty("isPermanent", placement == UpgradeCardFieldView.Placement.PERMANENT);
            Response<JsonObject> response = RequestCenter.postRequestJson(ResourceLocation.makeUri(ResourceLocation.activateUpgradePath(lobbyId, playerId)), info);
            if (!response.getStatusCode().is2xxSuccessful()) {
                Alert responseAlert = new Alert(Alert.AlertType.ERROR);
                responseAlert.setTitle("Error");
                responseAlert.setHeaderText(response.getItem().get("info").getAsString());
                responseAlert.showAndWait();
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void saveGame() {
        String fileName = inputBox(true);
        if (fileName == null) return;
        try {
            Response<JsonObject> response = RequestCenter.getRequestJson(ResourceLocation.makeUri(ResourceLocation.gameSavePath(client.getLobbyId())));
            if (!response.getStatusCode().is2xxSuccessful()) {
                Alert alert = new Alert(AlertType.ERROR);
                alert.setTitle("Error");
                alert.setHeaderText(response.getItem().get("info").getAsString());
                alert.showAndWait();
            }
            String finalName = LoadSave.getFilePath(fileName, LoadSave.GAMESFOLDER);

            GameTemplate gameState = gson.fromJson(response.getItem().getAsJsonObject().get("gameState").getAsString(), GameTemplate.class);
            LoadSave.writeToFile(gameState, finalName);
            //exit(); To exit or not to exit, that is the question

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void loadGame() {
        String fileName = inputBox(false);
        if (fileName == null) return;
        GameTemplate gameState = LoadSave.readGameStateFromFile(fileName);
        if (gameState == null) {
            Alert alert = new Alert(AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText("No game found with that name");
            alert.showAndWait();
            return;
        }
        try {
            JsonObject info = new JsonObject();
            info.addProperty("gameState", gson.toJson(gameState));
            info.addProperty("playerName", client.getPlayerName());
            Response<JsonObject> response = RequestCenter.postRequestJson(ResourceLocation.makeUri(ResourceLocation.gameLoadPath(client.getLobbyId())), info);
            if (!response.getStatusCode().is2xxSuccessful()) {
                Alert alert = new Alert(AlertType.ERROR);
                alert.setTitle("Error");
                alert.setHeaderText(response.getItem().get("info").getAsString());
                alert.showAndWait();
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }

        /*if (gameController == null) {
            String fileName = inputBox(false);
            if (fileName == null) return;
            gameController = loadGameState(fileName);
            if (gameController == null) return;
            roboRally.createBoardView(gameController);
        }*/
    }



    /**
     * Create a dialog box for the user to input a filename.
     * @author Kresten (s235103)
     * @param saving true if the dialog box is for saving a file, false if it is for loading a file
     * @return the filename input by the user
     */
    private String inputBox(boolean saving) {
        Label label = new Label(saving ? "Save game as:" : "Load game from:");
        TextField filenameField = new TextField();
        Button button = new Button(saving ? "Save" : "Load");
        button.setOnAction(e -> {
            Stage stage = (Stage) button.getScene().getWindow();
            stage.close();
        });
        Stage stage = new Stage();
        stage.setOnCloseRequest(e -> filenameField.setText(null));
        VBox root = new VBox();
        Scene scene = new Scene(root);
        stage.setScene(scene);
        stage.setTitle(saving ? "Save" : "Load" + " game");
        stage.setResizable(false);
        stage.setAlwaysOnTop(true);
        stage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        root.getChildren().addAll(label, filenameField, button);
        root.setPadding(new Insets(10));
        stage.showAndWait();

        return filenameField.getText();
    }

    public void exit() {
        Platform.exit();
    }

    @Override
    public void update(Subject subject) {
        // XXX do nothing for now
    }
}
