package util;

import java.util.Scanner;

public class InputUtil {
        public static Integer promptInt(Scanner scanner, String prompt) {
            System.out.print(prompt);
            String input = scanner.nextLine().trim();
            try {
                return Integer.parseInt(input);
            } catch (NumberFormatException e) {
                ConsoleUtil.clear();
                System.out.printf("\"%s\" is not a valid integer.%n", input);
                return null;
            }
        }
}
