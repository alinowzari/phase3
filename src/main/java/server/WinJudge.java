package server;

public final class WinJudge {

    public WinJudge() {}

    public MatchResult decide(PlayerStats a, PlayerStats b) {
        // Primary: who passed more levels
        if (a.levelsPassed() != b.levelsPassed()) {
            if(a.levelsPassed()){
                return new MatchResult(Winner.P1,"passed level",a,b);
            }
            else{
                return new MatchResult(Winner.P2,"passed level",a,b);
            }
        }
        else {
            if(a.coins()!=b.coins()) {
                if(a.coins()>b.coins()) {
                    return new MatchResult(Winner.P1,"more coins",a,b);
                }
                else {
                    return new MatchResult(Winner.P2,"more coins",a,b);
                }
            }
            else if(a.wireUsed()!=b.wireUsed()) {
                if(a.wireUsed()<b.wireUsed()) {
                    return new MatchResult(Winner.P1,"less wire",a,b);
                }
                else {
                    return new MatchResult(Winner.P2,"less wire",a,b);
                }
            }
        }
        return new MatchResult(Winner.DRAW, "Perfect tie", a, b);
    }
}