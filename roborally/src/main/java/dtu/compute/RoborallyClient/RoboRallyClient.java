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
package dtu.compute.RoborallyClient;

import com.google.gson.*;
import dtu.compute.RoborallyClient.controller.AppController;
import dtu.compute.RoborallyClient.fileaccess.model.GameTemplate;
import dtu.compute.RoborallyClient.online.RequestCenter;
import dtu.compute.RoborallyClient.online.Response;
import dtu.compute.RoborallyClient.view.BoardView;
import dtu.compute.RoborallyClient.view.MenuButtons;
import dtu.compute.RoborallyClient.view.RoboRallyMenuBar;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.scene.control.Button;
import lombok.Getter;
import lombok.Setter;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static dtu.compute.RoborallyClient.online.ResourceLocation.lobbyStatePath;
import static dtu.compute.RoborallyClient.online.ResourceLocation.makeUri;

/**
 * ...
 *
 * @author Ekkart Kindler, ekki@dtu.dk
 *
 */
@Getter
@Setter
public class RoboRallyClient extends Application {

    private static final int MIN_APP_WIDTH = 600;
    private boolean poll;
    private String lobbyId;
    private String playerName;
    private AppController appController;

    private static Stage stage;
    private BorderPane boardRoot;
    private VBox gameRoot;
    private static TilePane menuPane;
    private static TilePane lobbyPane;
    private static GridPane joinPane;
    private static Scene scene;

    private ScheduledExecutorService executorService;
    private BoardView boardView;
    private GameTemplate gameState;
    private String lastUpdate; // timestamp of last gameState update

    @Override
    public void init() throws Exception {
        super.init();
    }

    /**
     * Creates the stage and scene. The input into the scene changes depending on if the start menu is needed or the in game menu is needed.
     * @author Oscar (224752)
     */
    @Override
    public void start(Stage primaryStage) {
        stage = primaryStage;
        Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
        double screenWidth = screenBounds.getWidth();
        double screenHeight = screenBounds.getHeight();

        appController = new AppController(this);

        // create the primary scene with a menu bar and a pane for
        // the board view (which initially is empty); it will be filled
        // when the user creates a new game or loads a game
        RoboRallyMenuBar menuBar = new RoboRallyMenuBar(appController);
        MenuButtons menuButtons = new MenuButtons(appController);
        boardRoot = new BorderPane();
        gameRoot = new VBox(menuBar, boardRoot);
        gameRoot.setMinWidth(MIN_APP_WIDTH);
        menuPane = new TilePane(Orientation.VERTICAL);
        menuPane.getChildren().add(menuButtons.newGameButton);
        menuPane.getChildren().add(menuButtons.lobbyButton);
        menuPane.getChildren().add(menuButtons.exitGameButton);
        menuPane.getChildren().add(menuButtons.ruleButton);
        lobbyPane = new TilePane(Orientation.VERTICAL);
        joinPane = new GridPane();

        //style for the menu
        menuPane.setAlignment(Pos.CENTER);
        menuPane.setVgap(15);

        //style for the lobby
        lobbyPane.setAlignment(Pos.CENTER);
        lobbyPane.setVgap(15);
        lobbyPane.setPadding(new Insets(10));

        joinPane.setAlignment(Pos.TOP_LEFT);
        joinPane.setVgap(10);
        joinPane.setHgap(80);

        //Menu Background image
        Image menu = new Image("images/RoboRallyBackground.png");
        BackgroundImage backgroundMenu = new BackgroundImage(
                menu, BackgroundRepeat.NO_REPEAT, BackgroundRepeat.NO_REPEAT,
                BackgroundPosition.DEFAULT,
                new BackgroundSize(100, 100, true, true, true, true)
        );
        menuPane.setBackground(new Background(backgroundMenu));

        //Lobby Background image
        Image lobby = new Image("images/empty.png");
        BackgroundImage backgroundLobby = new BackgroundImage(
                lobby, BackgroundRepeat.REPEAT, BackgroundRepeat.REPEAT,
                BackgroundPosition.CENTER,
                new BackgroundSize(240, 240, false, false, false, false)
        );
        lobbyPane.setBackground(new Background(backgroundLobby));

        //Lobbies Background image
        Image lobbies = new Image("images/RoboRallyLobbyBackground.png");
        BackgroundImage backgroundLobbies = new BackgroundImage(
                lobbies, BackgroundRepeat.REPEAT, BackgroundRepeat.REPEAT,
                BackgroundPosition.CENTER,
                new BackgroundSize(100, 100, true, true, true, true)
        );
        joinPane.setBackground(new Background(backgroundLobbies));

        scene = new Scene(menuPane, screenWidth/1.5, screenHeight/1.5);
        stage.setScene(scene);
        stage.setTitle("RoboRally");
        stage.setOnCloseRequest(
                e -> {
                    e.consume();
                    if (lobbyId != null) {appController.leaveLobby();}
                        if (poll) {suspendPolling();}
                    appController.exit();}
        );
        stage.setResizable(true);
        stage.setMaximized(false);
        stage.show();
    }

    public void suspendPolling() {
        executorService.shutdown();
    }
    /**
     * This appoach must change if the timer is implemented.
     * Client will not start polling the server until they click the "Finish Programming" button.
     */
    public void startPolling() {
        executorService = Executors.newSingleThreadScheduledExecutor();
        executorService.scheduleAtFixedRate(appController::pollServer, 0, 500, TimeUnit.MILLISECONDS);
    }

    public void displayWinner() {
        Alert winnerAlert = new Alert(Alert.AlertType.INFORMATION);
        winnerAlert.setTitle("A player has won the game!");
        winnerAlert.setHeaderText("Congratulations to " + gameState.winnerName + "!\nThey have won the game!");
        winnerAlert.setContentText("Returning to main menu.");
        winnerAlert.showAndWait();
        appController.leaveLobby();
    }

    public void createLobbyView() {
        boardRoot.getChildren().clear();
        lobbyPane.getChildren().clear();
        stage.setMaximized(false);

        Text lobbyInfo = new Text("Lobby: " + lobbyId + "\nYour username: " + getPlayerName());
        lobbyInfo.setFont(Font.font("Arial", FontWeight.EXTRA_BOLD, 20));
        lobbyInfo.setTextAlignment(TextAlignment.LEFT);
        lobbyPane.getChildren().add(lobbyInfo);

        Text playersText = new Text("Players:");
        playersText.setFont(Font.font("Arial", FontWeight.EXTRA_BOLD, 20));
        playersText.setTextAlignment(TextAlignment.LEFT);
        lobbyPane.getChildren().add(playersText);

        Button startBtn = new Button("Start Game");
        Button loadBtn = new Button("Load Game");
        Button leaveBtn = new Button("Leave Lobby");
        startBtn.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> appController.createGame());
        loadBtn.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> appController.loadGame());
        leaveBtn.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> appController.leaveLobby());

        lobbyPane.getChildren().add(new HBox(15, startBtn, loadBtn, leaveBtn));
        stage.setScene(scene);
        scene.setRoot(lobbyPane);
    }

    public void createJoinView(Response<String> lobbies) throws IOException, InterruptedException {
        boardRoot.getChildren().clear();
        lobbyPane.getChildren().clear();
        joinPane.getChildren().clear();
        stage.setMaximized(false);
        DropShadow dropShadow = new DropShadow();
        dropShadow.setOffsetX(2.0);
        dropShadow.setOffsetY(2.0);
        dropShadow.setColor(Color.BLACK); // Shadow color


        Gson gson = new Gson();
        String jsonString = lobbies.getItem();
        JsonObject jsonObject = gson.fromJson(jsonString, JsonObject.class);

        JsonArray lobbiesArray = jsonObject.getAsJsonArray("lobbies");


        int row = 5;
        int column = 1;
        for (int i = 0; i < lobbiesArray.size(); i++) {
            String lobbyId = lobbiesArray.get(i).getAsString();

            Response<JsonObject> response = RequestCenter.getRequestJson(makeUri(lobbyStatePath(lobbyId)));
            JsonObject json = response.getItem();
            JsonArray players = json.get("players").getAsJsonArray();

            Text lobbyText = new Text("LobbyId: " + lobbyId);
            lobbyText.setFont(Font.font("Arial", FontWeight.EXTRA_BOLD, 25));
            lobbyText.setEffect(dropShadow);
            lobbyText.setFill(Color.WHITE);
            joinPane.add(lobbyText, column, row);
            row += 1;
            StringBuilder playerTextBuilder = new StringBuilder();
            playerTextBuilder.append("Players:");

            for (int x = 0; x < players.size(); x++) {
                playerTextBuilder.append("\nPlayer ").append(x + 1).append(": ").append(players.get(x).getAsString());
            }
            // Add lobby capacity information
            playerTextBuilder.append("\n").append(players.size()).append("/6");

            // Create and add the player text
            Text playerText = new Text(playerTextBuilder.toString());
            playerText.setFont(Font.font("Arial", FontWeight.EXTRA_BOLD, 20));
            playerText.setEffect(dropShadow);
            playerText.setFill(Color.WHITE);
            joinPane.add(playerText, column, row);
            row += 1;
            Button joinBtn = new Button("join Lobby");
            joinBtn.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> appController.joinLobby(lobbyId));
            joinPane.add(joinBtn, column, row);
            row -= 2;
            column += 1;
            if (column == 5) {
                column = 1;
                row += 5;
            }

        }

        Button leaveBtn = new Button("Back to menu");
        leaveBtn.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> stage.setScene(scene));
        StackPane stackPane = new StackPane();
        stackPane.getChildren().add(joinPane);
        stackPane.getChildren().add(leaveBtn);

        // Position the button to middle
        stackPane.widthProperty().addListener((obs, oldWidth, newWidth) -> {
            leaveBtn.setLayoutX(newWidth.doubleValue() - leaveBtn.getWidth() - 10);
        });
        stackPane.heightProperty().addListener((obs, oldHeight, newHeight) -> {
            leaveBtn.setLayoutY(newHeight.doubleValue() - leaveBtn.getHeight() - 10);
        });

        Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
        double screenWidth = screenBounds.getWidth();
        double screenHeight = screenBounds.getHeight();
        Scene sceneLobby = new Scene(stackPane, screenWidth/1.5, screenHeight/1.5);
        stage.setScene(sceneLobby);
        stage.show();
    }

    public void updateLobbyView(JsonObject lobbyContent) {
        if (lobbyContent == null) {
            return;
        }
        JsonArray players = lobbyContent.get("players").getAsJsonArray();
        String host = players.get(0).getAsString();
        Text info = (Text) lobbyPane.getChildren().get(0);
        String infoContent = info.getText();
        int hostStartIndex = infoContent.indexOf("\nHost: ");
        if (infoContent.contains("Host: ")) {
            infoContent = infoContent.substring(0, hostStartIndex);
        }
        info.setText(infoContent + "\nHost: " + host);

        Text text = ((Text) lobbyPane.getChildren().get(1));
        StringBuilder newText = new StringBuilder();
        newText.append("Players:");
        for (int i = 0; i < players.size(); i++) {
            newText.append("\nPlayer ").append(i+1).append(": ").append(players.get(i).getAsString());
        }
        if (!text.getText().contentEquals(newText)) {
            text.setText(newText.toString());
        }
    }

    public void createBoardView(GameTemplate gameState) {
        // if present, remove old BoardView
        boardRoot.getChildren().clear();
        stage.setMaximized(true);
        this.gameState = gameState;

        if (gameState != null) {
            // create and add view for new board
            boardView = new BoardView(appController, gameState, this);
            boardRoot.setCenter(boardView);
            //boardView.updateView(gameState.board); // TODO figure out what to do
            scene.setRoot(gameRoot);
            updateBoardView(gameState);
            startPolling();
        }
        //stage.setMaximized(true);
    }

    public void updateBoardView(GameTemplate gameState) {
        if (gameState != null) {
            this.gameState = gameState;
            BoardView boardView = (BoardView) boardRoot.getCenter();
            boardView.updateView(gameState);
        }
    }
    /**
     * changes the menu from the in game menu to the start menu.
     * @author Oscar (224752)
     */
    public void returnToMenu() {
        setPlayerName(null);
        setLobbyId(null);
        scene.setRoot(menuPane);
        stage.setMaximized(false);
        stage.show();
    }

    @Override
    public void stop() throws Exception {
        super.stop();

        // XXX just in case we need to do something here eventually;
        //     but right now the only way for the user to exit the app
        //     is delegated to the exit() method in the AppController,
        //     so that the AppController can take care of that.
    }

    public static void main(String[] args) {
        launch(args);
    }
}